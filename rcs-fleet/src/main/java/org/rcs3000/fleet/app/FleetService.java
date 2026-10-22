package org.rcs3000.fleet.app;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.rcs3000.common.error.BizException;
import org.rcs3000.common.event.EventBus;
import org.rcs3000.contracts.fleet.ConnectionStatus;
import org.rcs3000.contracts.fleet.OrderRefDto;
import org.rcs3000.contracts.fleet.RouteCommandDto;
import org.rcs3000.contracts.fleet.VehicleConnectionChangedEvent;
import org.rcs3000.contracts.fleet.VehicleForgottenEvent;
import org.rcs3000.contracts.fleet.VehicleStateUpdatedEvent;
import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.fleet.core.FleetErrorCode;
import org.rcs3000.fleet.core.VehicleSession;
import org.rcs3000.fleet.core.VehicleStatusCache;
import org.rcs3000.vda5050.client.MasterClient;
import org.rcs3000.vda5050.client.MasterListener;
import org.rcs3000.vda5050.client.VehicleId;
import org.rcs3000.vda5050.v2.Action;
import org.rcs3000.vda5050.v2.ActionParameter;
import org.rcs3000.vda5050.v2.ActionTypes;
import org.rcs3000.vda5050.v2.BlockingType;
import org.rcs3000.vda5050.v2.Connection;
import org.rcs3000.vda5050.v2.Header;
import org.rcs3000.vda5050.v2.InstantActions;
import org.rcs3000.vda5050.v2.Order;
import org.rcs3000.vda5050.v2.State;
import org.rcs3000.vda5050.v2.factsheet.Factsheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The fleet service's use cases (requirements FLT-01..05, FLT-08, VEH-03..07). Vehicles are discovered
 * from their VDA 5050 messages; the vehicle code is the serial number until the vehicle registry in
 * rcs-resource exists.
 */
public class FleetService implements MasterListener {

    private static final Logger log = LoggerFactory.getLogger(FleetService.class);

    static final Duration RESEND_AFTER = Duration.ofSeconds(3);
    static final int MAX_RESENDS = 3;

    private final MasterClient master;
    private final EventBus events;
    private final VehicleStatusCache cache;
    private final Clock clock;
    private final Duration heartbeatTimeout;
    private final Map<String, VehicleSession> sessions = new ConcurrentHashMap<>();

    public FleetService(MasterClient master, EventBus events, VehicleStatusCache cache, Clock clock,
            Duration heartbeatTimeout) {
        this.master = master;
        this.events = events;
        this.cache = cache;
        this.clock = clock;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public void start() {
        master.start(this);
    }

    public void stop() {
        master.close();
    }

    // ------------------------------------------------------------------ incoming

    @Override
    public void onConnection(VehicleId vehicle, Connection connection) {
        session(vehicle).ifPresent(s -> {
            s.onConnection(connection, clock.instant()).ifPresent(this::connectionChanged);
            cache.put(s.status());
        });
    }

    @Override
    public void onState(VehicleId vehicle, State state) {
        session(vehicle).ifPresent(s -> {
            s.onState(state, clock.instant()).ifPresent(this::connectionChanged);
            VehicleStatusDto status = s.status();
            cache.put(status);
            events.publish(VehicleStateUpdatedEvent.TYPE, new VehicleStateUpdatedEvent(status));
        });
    }

    @Override
    public void onFactsheet(VehicleId vehicle, Factsheet factsheet) {
        session(vehicle).ifPresent(s -> s.onFactsheet(factsheet, clock.instant()));
    }

    /**
     * Marks vehicles that went silent (VEH-07) and sends lost orders again (FLT-06); called
     * periodically.
     */
    public void checkHeartbeats() {
        for (VehicleSession s : sessions.values()) {
            s.orderToResend(clock.instant(), RESEND_AFTER, MAX_RESENDS).ifPresent(order -> {
                log.warn("vehicle {} does not report order {} update {}; sending it again", s.code(), order.orderId(),
                        order.orderUpdateId());
                master.sendOrder(s.id(), order);
            });
            s.checkHeartbeat(clock.instant(), heartbeatTimeout).ifPresent(change -> {
                connectionChanged(change);
                cache.put(s.status());
            });
        }
    }

    private void connectionChanged(VehicleConnectionChangedEvent change) {
        log.info("vehicle {}: {} -> {} ({})", change.vehicleCode(), change.previous(), change.current(), change.reason());
        events.publish(VehicleConnectionChangedEvent.TYPE, change);
    }

    private Optional<VehicleSession> session(VehicleId id) {
        VehicleSession s = sessions.computeIfAbsent(id.serialNumber(), code -> new VehicleSession(code, id));
        if (!s.id().equals(id)) {
            log.warn("ignoring {}: serial number {} already belongs to {}", id, id.serialNumber(), s.id());
            return Optional.empty();
        }
        return Optional.of(s);
    }

    // ------------------------------------------------------------------ queries

    public List<VehicleStatusDto> vehicles() {
        return sessions.values().stream().map(VehicleSession::status)
                .sorted(Comparator.comparing(VehicleStatusDto::code)).toList();
    }

    public VehicleStatusDto vehicle(String code) {
        return require(code).status();
    }

    public Factsheet factsheet(String code) {
        return require(code).factsheet().orElseThrow(() -> new BizException(FleetErrorCode.VEHICLE_NOT_FOUND,
                "vehicle %s has not sent a factsheet yet".formatted(code)));
    }

    /**
     * Drops a vehicle that is no longer online, e.g. a deleted simulated vehicle, so it stops showing
     * up as offline. Should it send a message again it comes back as a new vehicle.
     */
    public void forget(String code) {
        VehicleSession s = require(code);
        if (s.connection() == ConnectionStatus.ONLINE) {
            throw new BizException(FleetErrorCode.VEHICLE_STILL_ONLINE,
                    "vehicle %s is online; only vehicles that left can be removed".formatted(code));
        }
        sessions.remove(code, s);
        cache.remove(code);
        // otherwise its last will, retained by the broker, would bring it back after a restart
        master.clearRetained(s.id());
        log.info("vehicle {} removed", code);
        events.publish(VehicleForgottenEvent.TYPE, new VehicleForgottenEvent(code, clock.instant()));
    }

    // ------------------------------------------------------------------ commands

    /** Sends a new order (FLT-02). The vehicle reports acceptance or rejection in its state. */
    public OrderRefDto sendRoute(String code, RouteCommandDto route) {
        VehicleSession s = requireOnline(code);
        String orderId = code + "-" + UUID.randomUUID().toString().substring(0, 8);
        Order order = s.newOrder(orderId, route);
        master.sendOrder(s.id(), order);
        s.orderSent(order, clock.instant());
        return new OrderRefDto(code, order.orderId(), order.orderUpdateId());
    }

    /** Extends the current order from its decision point and replaces the horizon. */
    public OrderRefDto extendRoute(String code, RouteCommandDto route) {
        VehicleSession s = requireOnline(code);
        Order order = s.updateOrder(route);
        master.sendOrder(s.id(), order);
        s.orderSent(order, clock.instant());
        return new OrderRefDto(code, order.orderId(), order.orderUpdateId());
    }

    public String pause(String code) {
        return instant(code, ActionTypes.START_PAUSE);
    }

    public String resume(String code) {
        return instant(code, ActionTypes.STOP_PAUSE);
    }

    public String cancelOrder(String code) {
        return instant(code, ActionTypes.CANCEL_ORDER);
    }

    /** Ends charging so the vehicle can leave the charger, e.g. for a new transport (DSP-07). */
    public String stopCharging(String code) {
        return instant(code, ActionTypes.STOP_CHARGING);
    }

    public String requestFactsheet(String code) {
        return instant(code, ActionTypes.FACTSHEET_REQUEST);
    }

    /** Tells the vehicle where it stands (VEH-06). */
    public String relocate(String code, double x, double y, double theta, String mapCode, String pointCode) {
        return instant(code, ActionTypes.INIT_POSITION,
                new ActionParameter("x", x), new ActionParameter("y", y), new ActionParameter("theta", theta),
                new ActionParameter("mapId", mapCode == null ? "" : mapCode),
                new ActionParameter("lastNodeId", pointCode == null ? "" : pointCode));
    }

    private String instant(String code, String actionType, ActionParameter... parameters) {
        VehicleSession s = requireOnline(code);
        String actionId = actionType + "-" + UUID.randomUUID().toString().substring(0, 8);
        Action action = new Action(actionType, actionId, null, BlockingType.HARD,
                parameters.length == 0 ? null : List.of(parameters));
        master.sendInstantActions(s.id(), new InstantActions(Header.UNSTAMPED, List.of(action)));
        return actionId;
    }

    private VehicleSession require(String code) {
        VehicleSession s = sessions.get(code);
        if (s == null) {
            throw new BizException(FleetErrorCode.VEHICLE_NOT_FOUND, "vehicle %s is unknown".formatted(code));
        }
        return s;
    }

    private VehicleSession requireOnline(String code) {
        VehicleSession s = require(code);
        if (s.connection() != ConnectionStatus.ONLINE) {
            throw new BizException(FleetErrorCode.VEHICLE_NOT_ONLINE,
                    "vehicle %s is %s".formatted(code, s.connection()));
        }
        return s;
    }
}
