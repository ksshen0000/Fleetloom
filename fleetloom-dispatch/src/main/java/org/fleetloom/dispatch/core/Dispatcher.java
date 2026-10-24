package org.fleetloom.dispatch.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.fleetloom.common.error.BizException;
import org.fleetloom.common.event.EventBus;
import org.fleetloom.contracts.dispatch.CreateOrderDto;
import org.fleetloom.contracts.dispatch.OrderStatus;
import org.fleetloom.contracts.dispatch.OrderOverdueEvent;
import org.fleetloom.contracts.dispatch.OrderStatusChangedEvent;
import org.fleetloom.contracts.dispatch.OrderType;
import org.fleetloom.contracts.dispatch.OrderViewDto;
import org.fleetloom.contracts.fleet.ConnectionStatus;
import org.fleetloom.contracts.fleet.VehicleActionDto;
import org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent;
import org.fleetloom.contracts.fleet.VehicleStatusDto;
import org.fleetloom.contracts.storage.TransportLockDto;
import org.fleetloom.contracts.traffic.CostQueryDto;
import org.fleetloom.contracts.traffic.RouteProgressedEvent;
import org.fleetloom.contracts.traffic.RouteStatus;
import org.fleetloom.contracts.traffic.RouteViewDto;
import org.fleetloom.contracts.traffic.TrafficBlockedEvent;
import org.fleetloom.dispatch.core.Ports.CarrierInfo;
import org.fleetloom.dispatch.core.Ports.FleetGateway;
import org.fleetloom.dispatch.core.Ports.LocationInfo;
import org.fleetloom.dispatch.core.Ports.OrderRepository;
import org.fleetloom.dispatch.core.Ports.RegisteredVehicle;
import org.fleetloom.dispatch.core.Ports.ResourceGateway;
import org.fleetloom.dispatch.core.Ports.TemplateRepository;
import org.fleetloom.dispatch.core.Ports.TrafficGateway;
import org.fleetloom.dispatch.core.Ports.VehicleSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns transport orders into vehicle work (requirements DSP-01..10): validates and locks the storage
 * side, picks the cheapest suitable vehicle, sends it leg by leg through the traffic service, records
 * pick-up and drop-off, and sends idle vehicles with a low battery to charge.
 *
 * <p>Not thread-safe by design: one thread drives it, so a vehicle can never get two orders.
 */
public final class Dispatcher {

    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    private static final int MAX_TARGET_ATTEMPTS = 3;

    /** Error code of the traffic service while a vehicle still finishes an earlier order. */
    static final String VEHICLE_BUSY = "VEHICLE_BUSY";

    /**
     * Errors of a route request that usually pass by themselves: a service is restarting, or traffic
     * control has not heard of the map or the vehicle again yet; or, for the storage update after a pick
     * or drop, fleetloom-resource is restarting. Retried for {@link #RETRY_PASSING_FOR} before the order is
     * suspended.
     */
    static final Set<String> PASSING = Set.of("UNAVAILABLE", "FLEET_UNAVAILABLE", "MAP_UNAVAILABLE", "VEHICLE_UNKNOWN");
    static final Duration RETRY_PASSING_FOR = Duration.ofMinutes(2);

    /** Order numbers of the parking orders dispatch makes up itself (DSP-08). */
    static final String PARKING = "PK-";

    private final OrderRepository orders;
    private final TemplateRepository templates;
    private final VehicleSettingsRepository vehicleSettings;
    private final ResourceGateway resource;
    private final TrafficGateway traffic;
    private final FleetGateway fleet;
    private final EventBus events;
    private DispatchSettings settings;
    private final Clock clock;

    /** Live vehicles keyed by their fleet code. */
    private final Map<String, DispatchVehicle> vehicles = new LinkedHashMap<>();
    private final Map<String, TransportOrder> active = new LinkedHashMap<>();
    private Set<String> notAccepting = new HashSet<>();
    private List<RegisteredVehicle> registry = List.of();
    private Map<String, Set<String>> compatibility = Map.of();
    private Instant registryLoadedAt;
    private Instant routesPolledAt;

    public Dispatcher(OrderRepository orders, TemplateRepository templates, VehicleSettingsRepository vehicleSettings,
            ResourceGateway resource, TrafficGateway traffic, FleetGateway fleet, EventBus events, DispatchSettings settings,
            Clock clock) {
        this.orders = orders;
        this.templates = templates;
        this.vehicleSettings = vehicleSettings;
        this.resource = resource;
        this.traffic = traffic;
        this.fleet = fleet;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    public DispatchSettings settings() {
        return settings;
    }

    /** Takes effect from the next order created and the next tick on. */
    public void updateSettings(DispatchSettings settings) {
        this.settings = settings;
    }

    /** Picks up unfinished orders after a restart. */
    public void start() {
        orders.findActive().forEach(o -> active.put(o.orderNo, o));
        notAccepting = new HashSet<>(vehicleSettings.notAccepting());
        log.info("dispatcher started with {} unfinished order(s)", active.size());
    }

    // ================================================================== orders

    public OrderViewDto create(CreateOrderDto request) {
        if (request.externalNo() != null && !request.externalNo().isBlank()) {
            Optional<TransportOrder> existing = orders.findByExternalNo(request.externalNo());
            if (existing.isPresent()) {
                return existing.get().view();
            }
        }
        if (request.type() == null || request.type() == OrderType.CHARGE) {
            throw new BizException(DispatchErrorCode.INVALID_ORDER, "order type must be TRANSPORT or MOVE");
        }
        int priority = request.priority() == null ? settings.defaultPriority() : request.priority();
        if (priority < 1 || priority > 9) {
            throw new BizException(DispatchErrorCode.INVALID_ORDER, "priority must be between 1 and 9");
        }
        Instant now = clock.instant();
        String orderNo = "TO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TransportOrder order = new TransportOrder(null, orderNo, blankToNull(request.externalNo()), request.type(), priority,
                request.carrierCode(), null, null, null, request.mapCode(), blankToNull(request.vehicleCode()), null,
                OrderStatus.QUEUED, 0, List.of(), null, null, now, now, null, 0).withDeadline(request.deadline());
        try {
            if (request.type() == OrderType.TRANSPORT) {
                prepareTransport(order, request);
            } else {
                prepareMove(order, request);
            }
        } catch (BizException | RemoteCallException e) {
            order.status = OrderStatus.REJECTED;
            order.reason = e.getMessage();
            order.finishedAt = now;
        }
        orders.insert(order);
        if (!order.status.isFinal()) {
            active.put(orderNo, order);
        }
        events.publish(OrderStatusChangedEvent.TYPE, new OrderStatusChangedEvent(order.view(), null));
        assignWaitingOrders();
        return order.view();
    }

    private void prepareTransport(TransportOrder order, CreateOrderDto request) {
        if (request.carrierCode() == null || request.carrierCode().isBlank()) {
            throw new BizException(DispatchErrorCode.INVALID_ORDER, "a transport needs a carrier");
        }
        // checked before anything is locked
        OrderTemplate template = template(request.template());
        CarrierInfo carrier = resource.carrier(request.carrierCode()).orElseThrow(() ->
                new BizException(DispatchErrorCode.INVALID_ORDER, "carrier %s not found".formatted(request.carrierCode())));
        if (!"STORAGE".equals(carrier.locationType())) {
            throw new BizException(DispatchErrorCode.INVALID_ORDER,
                    "carrier %s is not in a storage location".formatted(carrier.code()));
        }
        List<String> targets;
        if (request.targetLocation() != null && !request.targetLocation().isBlank()) {
            targets = List.of(request.targetLocation());
        } else if (request.targetZone() != null && !request.targetZone().isBlank()) {
            Optional<LocationInfo> source = resource.location(carrier.locationRef());
            targets = resource.availableLocations(request.targetZone(), carrier.typeCode(),
                    source.map(LocationInfo::mapCode).orElse(null), source.map(LocationInfo::accessPoint).orElse(null));
            if (targets.isEmpty()) {
                throw new BizException(DispatchErrorCode.INVALID_ORDER,
                        "no empty location for %s in zone %s".formatted(carrier.typeCode(), request.targetZone()));
            }
        } else {
            throw new BizException(DispatchErrorCode.INVALID_ORDER, "a transport needs a target location or zone");
        }
        TransportLockDto lock = lockFirstAvailable(order.orderNo, carrier.code(), targets);
        if (!lock.sourceMap().equals(lock.targetMap())) {
            resource.release(order.orderNo, List.of(lock.sourceLocation(), lock.targetLocation()));
            throw new BizException(DispatchErrorCode.INVALID_ORDER, "source and target are on different maps");
        }
        order.carrierType = lock.carrierType();
        order.sourceLocation = lock.sourceLocation();
        order.targetLocation = lock.targetLocation();
        order.mapCode = lock.sourceMap();
        Map<String, Object> load = Map.of("loadId", carrier.code());
        order.legs.add(new Leg(Leg.Kind.PICKUP, lock.sourcePoint(), template.pickAction(), load, Leg.Status.PENDING));
        for (String via : template.viaPoints()) {
            order.legs.add(new Leg(Leg.Kind.MOVE, via, template.viaAction(), Map.of(), Leg.Status.PENDING));
        }
        order.legs.add(new Leg(Leg.Kind.DROP, lock.targetPoint(), template.dropAction(), load, Leg.Status.PENDING));
    }

    /** DSP-03: the named template, or the standard one; a missing standard template falls back to pick / drop. */
    private OrderTemplate template(String code) {
        if (code == null || code.isBlank()) {
            return templates.find(OrderTemplate.STANDARD).orElse(OrderTemplate.standard());
        }
        return templates.find(code.trim()).orElseThrow(() ->
                new BizException(DispatchErrorCode.INVALID_ORDER, "order template %s not found".formatted(code)));
    }

    /** Another order may grab a target between the query and the lock; try the next candidates then. */
    private TransportLockDto lockFirstAvailable(String owner, String carrier, List<String> targets) {
        RemoteCallException last = null;
        for (String target : targets.subList(0, Math.min(targets.size(), MAX_TARGET_ATTEMPTS))) {
            try {
                return resource.lock(owner, carrier, target);
            } catch (RemoteCallException e) {
                last = e;
            }
        }
        throw last;
    }

    private void prepareMove(TransportOrder order, CreateOrderDto request) {
        if (request.mapCode() == null || request.targetPoint() == null) {
            throw new BizException(DispatchErrorCode.INVALID_ORDER, "a move needs a map and a target point");
        }
        order.legs.add(new Leg(Leg.Kind.MOVE, request.targetPoint(), null, Map.of(), Leg.Status.PENDING));
    }

    public OrderViewDto cancel(String orderNo) {
        TransportOrder order = requireActive(orderNo);
        if (order.carrierOnVehicle()) {
            throw new BizException(DispatchErrorCode.CARRIER_ON_VEHICLE,
                    "carrier %s is already on vehicle %s; let the order finish or resolve it by hand"
                            .formatted(order.carrierCode, order.assignedVehicle));
        }
        if (order.assignedVehicle != null) {
            stopRoute(order);
        }
        releaseLocks(order);
        finish(order, OrderStatus.CANCELLED, "cancelled");
        return order.view();
    }

    /** Stops an order by hand (DSP-09); the vehicle stops where it is. */
    public OrderViewDto suspend(String orderNo) {
        TransportOrder order = requireActive(orderNo);
        if (order.status != OrderStatus.EXECUTING) {
            throw new BizException(DispatchErrorCode.ORDER_STATE_INVALID, "order %s is %s".formatted(orderNo, order.status));
        }
        stopRoute(order);
        suspend(order, "suspended by hand");
        return order.view();
    }

    /** Continues a suspended order with the leg it stopped in. */
    public OrderViewDto resume(String orderNo) {
        TransportOrder order = requireActive(orderNo);
        if (order.status != OrderStatus.SUSPENDED) {
            throw new BizException(DispatchErrorCode.ORDER_STATE_INVALID, "order %s is %s".formatted(orderNo, order.status));
        }
        order.reason = null;
        if (order.assignedVehicle == null) {
            transition(order, OrderStatus.QUEUED, null);
        } else {
            transition(order, OrderStatus.EXECUTING, null);
            startLeg(order);
        }
        return order.view();
    }

    public OrderViewDto get(String orderNo) {
        TransportOrder order = active.get(orderNo);
        if (order != null) {
            return order.view();
        }
        return orders.findByNo(orderNo).map(TransportOrder::view).orElseThrow(() ->
                new BizException(DispatchErrorCode.ORDER_NOT_FOUND, "order %s not found".formatted(orderNo)));
    }

    public List<OrderViewDto> list(OrderStatus status, int limit) {
        return orders.find(status, limit).stream()
                .map(o -> active.getOrDefault(o.orderNo, o).view())
                .toList();
    }

    // ================================================================== vehicles

    public void setAccepting(String vehicleCode, boolean accepting) {
        if (!vehicles.containsKey(vehicleCode)) {
            throw new BizException(DispatchErrorCode.VEHICLE_UNKNOWN, "vehicle %s is unknown".formatted(vehicleCode));
        }
        vehicleSettings.setAccepting(vehicleCode, accepting);
        if (accepting) {
            notAccepting.remove(vehicleCode);
        } else {
            notAccepting.add(vehicleCode);
        }
    }

    public List<VehicleView> vehicles() {
        refreshRegistryIfDue(false);
        return vehicles.values().stream()
                .map(v -> new VehicleView(v.code, v.registered == null ? null : v.registered.code(),
                        !notAccepting.contains(v.code), v.connection, v.status.battery(), v.status.charging(),
                        v.order, unavailableReason(v, null)))
                .toList();
    }

    /** @param unavailable why the vehicle cannot take an order right now, or {@code null} if it can */
    public record VehicleView(String code, String registeredAs, boolean accepting, ConnectionStatus connection,
            double battery, boolean charging, String currentOrder, String unavailable) {
    }

    public void onVehicleStatus(VehicleStatusDto status) {
        DispatchVehicle v = vehicles.computeIfAbsent(status.code(), code -> {
            DispatchVehicle created = new DispatchVehicle(code);
            // after a restart: the vehicle may already work on an unfinished order
            active.values().stream().filter(o -> code.equals(o.assignedVehicle) && !o.status.isFinal())
                    .findFirst().ifPresent(o -> created.order = o.orderNo);
            return created;
        });
        v.status = status;
        v.connection = status.connection();
        if (v.order == null) {
            return;
        }
        if (!status.charging()) {
            startPendingLegs();
        }
        TransportOrder order = active.get(v.order);
        if (order == null || order.status != OrderStatus.EXECUTING) {
            return;
        }
        if (status.connection() != ConnectionStatus.ONLINE) {
            suspend(order, "vehicle %s is %s".formatted(v.code, status.connection()));
        } else if (status.errors().stream().anyMatch(e -> "FATAL".equals(e.level()))) {
            String error = status.errors().stream().filter(e -> "FATAL".equals(e.level()))
                    .map(e -> e.errorType() + ": " + e.description()).findFirst().orElse("fatal error");
            stopRoute(order);
            suspend(order, "vehicle %s reports %s".formatted(v.code, error));
        }
    }

    /** A vehicle went offline or came back (VEH-07); its running order is suspended (DSP-10). */
    public void onConnectionChanged(VehicleConnectionChangedEvent event) {
        DispatchVehicle v = vehicles.get(event.vehicleCode());
        if (v == null) {
            return;
        }
        v.connection = event.current();
        TransportOrder order = v.order == null ? null : active.get(v.order);
        if (order != null && order.status == OrderStatus.EXECUTING && event.current() != ConnectionStatus.ONLINE) {
            suspend(order, "vehicle %s is %s (%s)".formatted(v.code, event.current(), event.reason()));
        }
    }

    /**
     * The vehicle was removed from fleet control. One still bound to an order stays known until that
     * order is cancelled, so the order keeps pointing at something real.
     */
    public void onVehicleForgotten(String vehicleCode) {
        DispatchVehicle v = vehicles.get(vehicleCode);
        if (v != null && v.order == null) {
            vehicles.remove(vehicleCode);
        }
    }

    /**
     * A vehicle waits for one that has nothing to do (TRF-17): send the idle one to the nearest free
     * point off the waiting vehicle's route.
     */
    public void onTrafficBlocked(TrafficBlockedEvent event) {
        DispatchVehicle blocker = vehicles.get(event.blockedBy());
        if (blocker == null || blocker.order != null || blocker.connection != ConnectionStatus.ONLINE
                || !onMap(blocker.status)) {
            return;
        }
        String map = blocker.status.mapCode();
        Set<String> avoid = new HashSet<>();
        vehicles.values().forEach(v -> avoid.add(v.status.lastNodeId()));
        active.values().forEach(o -> o.legs.forEach(l -> avoid.add(l.point())));
        List<String> free;
        try {
            traffic.route(event.vehicleCode()).ifPresent(r -> avoid.addAll(r.nodes()));
            free = resource.waitingPoints(map).stream().filter(p -> !avoid.contains(p)).toList();
        } catch (RemoteCallException e) {
            log.warn("could not find a place for {} to give way: {}", blocker.code, e.getMessage());
            return;
        }
        Optional<String> spot = nearest(map, blocker.status.lastNodeId(), free);
        if (spot.isEmpty()) {
            log.warn("{} blocks {} but there is no free point to move it to", blocker.code, event.vehicleCode());
            return;
        }
        Instant now = clock.instant();
        TransportOrder order = new TransportOrder(null, "GW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                null, OrderType.MOVE, 9, null, null, null, null, map, blocker.code, null, OrderStatus.QUEUED, 0,
                List.of(new Leg(Leg.Kind.MOVE, spot.get(), null, Map.of(), Leg.Status.PENDING)),
                "give way to " + event.vehicleCode(), null, now, now, null, 0);
        orders.insert(order);
        active.put(order.orderNo, order);
        events.publish(OrderStatusChangedEvent.TYPE, new OrderStatusChangedEvent(order.view(), null));
        log.info("{} gives way to {}: moving to {}", blocker.code, event.vehicleCode(), spot.get());
        assign(order, blocker);
    }

    /**
     * TRF-16 for the deadlocks dispatch causes itself: a vehicle that is only on its way to park
     * (DSP-08) gives the parking up, and moves out of the way of the vehicles waiting for it.
     * Deadlocks between vehicles with real work are left to the operator (the alarm).
     */
    public void onDeadlock(List<String> vehicleCodes) {
        for (String code : vehicleCodes) {
            DispatchVehicle v = vehicles.get(code);
            TransportOrder order = v == null || v.order == null ? null : active.get(v.order);
            if (order == null || !order.orderNo.startsWith(PARKING)) {
                continue;
            }
            log.warn("{} is in a deadlock on its way to park: parking cancelled", code);
            stopRoute(order);
            finish(order, OrderStatus.CANCELLED, "deadlock on the way to park");
            v.idleSince = null;
            for (String other : vehicleCodes) {
                if (other.equals(code)) {
                    continue;
                }
                try {
                    traffic.route(other).filter(r -> code.equals(r.blockedBy())).ifPresent(r -> onTrafficBlocked(
                            new TrafficBlockedEvent(other, code, r.blockedResource(), clock.instant())));
                } catch (RemoteCallException e) {
                    log.warn("could not look up the route of {}: {}", other, e.getMessage());
                }
            }
        }
    }

    public void onRouteProgressed(RouteProgressedEvent event) {
        active.values().stream()
                .filter(o -> o.status == OrderStatus.EXECUTING && event.routeId().equals(o.routeId))
                .findFirst()
                .ifPresent(o -> routeChanged(o, event.status(), event.reason()));
    }

    // ================================================================== periodic work

    public void tick() {
        refreshRegistryIfDue(false);
        Instant now = clock.instant();
        if (routesPolledAt == null || Duration.between(routesPolledAt, now).compareTo(settings.routePollInterval()) >= 0) {
            routesPolledAt = now;
            pollRoutes();
        }
        startPendingLegs();
        sendLowBatteriesToCharge();
        assignWaitingOrders();
        parkIdleVehicles();
        reportOverdueOrders();
    }

    /** DSP-10: tells once per order that it missed its deadline. */
    private void reportOverdueOrders() {
        Instant now = clock.instant();
        for (TransportOrder order : active.values()) {
            if (order.deadline != null && !order.overdueReported && now.isAfter(order.deadline)) {
                order.overdueReported = true;
                events.publish(OrderOverdueEvent.TYPE, new OrderOverdueEvent(order.view()));
                log.warn("order {} is not finished by its deadline {}", order.orderNo, order.deadline);
            }
        }
    }

    /**
     * Catches route outcomes whose event was missed, e.g. during a restart. A route traffic control
     * no longer knows (it restarted and holds only where the vehicles stand) is requested again, so
     * the order goes on from wherever the vehicle stopped (NFR-03).
     */
    private void pollRoutes() {
        for (TransportOrder order : List.copyOf(active.values())) {
            if (order.status != OrderStatus.EXECUTING || order.routeId == null) {
                continue;
            }
            try {
                Optional<RouteViewDto> route = traffic.route(order.assignedVehicle);
                if (route.isPresent() && route.get().routeId().equals(order.routeId)) {
                    if (!route.get().status().isActive()) {
                        routeChanged(order, route.get().status(), "from route poll");
                    }
                } else if (route.isEmpty() || !route.get().status().isActive()) {
                    log.warn("route {} of order {} is unknown to traffic control; requesting it again",
                            order.routeId, order.orderNo);
                    requestLegAgain(order);
                }
            } catch (RemoteCallException e) {
                log.warn("could not poll the route of {}: {}", order.assignedVehicle, e.getMessage());
            }
        }
    }

    private void assignWaitingOrders() {
        refreshRegistryIfDue(false);
        List<TransportOrder> queued = active.values().stream()
                .filter(o -> o.status == OrderStatus.QUEUED)
                .sorted(Comparator.comparingInt((TransportOrder o) -> -o.priority).thenComparing(o -> o.createdAt))
                .toList();
        for (TransportOrder order : queued) {
            List<DispatchVehicle> candidates = vehicles.values().stream()
                    .filter(v -> unavailableReason(v, order) == null)
                    .toList();
            if (candidates.isEmpty()) {
                continue;
            }
            cheapest(order.mapCode, candidates, order.leg().point()).ifPresent(v -> assign(order, v));
        }
    }

    private void sendLowBatteriesToCharge() {
        for (DispatchVehicle v : List.copyOf(vehicles.values())) {
            VehicleStatusDto s = v.status;
            if (v.order != null || v.registered == null || !v.registered.enabled()
                    || v.connection != ConnectionStatus.ONLINE || s.charging() || !onMap(s)
                    || s.battery() >= v.registered.chargeThresholdLow()) {
                continue;
            }
            Set<String> taken = new HashSet<>();
            active.values().stream().filter(o -> o.type == OrderType.CHARGE).forEach(o -> taken.add(o.leg().point()));
            vehicles.values().stream().filter(o -> o != v).forEach(o -> taken.add(o.status.lastNodeId()));
            List<String> free;
            try {
                free = resource.chargingPoints(s.mapCode()).stream().filter(p -> !taken.contains(p)).toList();
            } catch (RemoteCallException e) {
                log.warn("could not look up charging points of map {}: {}", s.mapCode(), e.getMessage());
                continue;
            }
            Optional<String> point = nearest(s.mapCode(), s.lastNodeId(), free);
            if (point.isEmpty()) {
                continue;
            }
            Instant now = clock.instant();
            TransportOrder order = new TransportOrder(null, "CH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    null, OrderType.CHARGE, 9, null, null, null, null, s.mapCode(), v.code, null, OrderStatus.QUEUED, 0,
                    List.of(new Leg(Leg.Kind.CHARGE, point.get(), "startCharging", Map.of(), Leg.Status.PENDING)),
                    "battery %.0f %% below %.0f %%".formatted(s.battery(), v.registered.chargeThresholdLow()),
                    null, now, now, null, 0);
            orders.insert(order);
            active.put(order.orderNo, order);
            events.publish(OrderStatusChangedEvent.TYPE, new OrderStatusChangedEvent(order.view(), null));
            assign(order, v);
        }
    }

    /**
     * DSP-08: a vehicle that has had nothing to do for a while, and does not stand on a parking point,
     * goes to the nearest free one so it does not block aisles, stations or chargers.
     */
    private void parkIdleVehicles() {
        Duration after = settings.parkAfterIdle();
        if (after.isZero() || after.isNegative()) {
            return;
        }
        Instant now = clock.instant();
        for (DispatchVehicle v : List.copyOf(vehicles.values())) {
            VehicleStatusDto s = v.status;
            boolean idle = v.order == null && v.connection == ConnectionStatus.ONLINE && onMap(s) && !s.driving()
                    && !s.charging() && s.remainingNodes().isEmpty() && v.registered != null && v.registered.enabled()
                    && !notAccepting.contains(v.code);
            if (!idle || s.lastNodeId().equals(v.parkedAt)) {
                v.idleSince = idle ? v.idleSince : null;
                continue;
            }
            if (v.idleSince == null) {
                v.idleSince = now;
                continue;
            }
            if (Duration.between(v.idleSince, now).compareTo(after) < 0) {
                continue;
            }
            v.idleSince = now;
            List<String> parking;
            try {
                parking = resource.parkingPoints(s.mapCode());
            } catch (RemoteCallException e) {
                log.warn("could not look up parking points of map {}: {}", s.mapCode(), e.getMessage());
                continue;
            }
            if (parking.contains(s.lastNodeId())) {
                v.parkedAt = s.lastNodeId();
                continue;
            }
            Set<String> taken = new HashSet<>();
            vehicles.values().stream().filter(o -> o != v).forEach(o -> taken.add(o.status.lastNodeId()));
            active.values().forEach(o -> o.legs.forEach(l -> taken.add(l.point())));
            Optional<String> spot = nearest(s.mapCode(), s.lastNodeId(), parking.stream().filter(p -> !taken.contains(p)).toList());
            if (spot.isEmpty()) {
                continue;
            }
            TransportOrder order = new TransportOrder(null, PARKING + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
                    null, OrderType.MOVE, 1, null, null, null, null, s.mapCode(), v.code, null, OrderStatus.QUEUED, 0,
                    List.of(new Leg(Leg.Kind.MOVE, spot.get(), null, Map.of(), Leg.Status.PENDING)),
                    "idle for %d s, parking".formatted(after.toSeconds()), null, now, now, null, 0);
            orders.insert(order);
            active.put(order.orderNo, order);
            events.publish(OrderStatusChangedEvent.TYPE, new OrderStatusChangedEvent(order.view(), null));
            log.info("{} idle for {} s at {}: parking at {}", v.code, after.toSeconds(), s.lastNodeId(), spot.get());
            assign(order, v);
        }
    }

    // ================================================================== execution

    private void assign(TransportOrder order, DispatchVehicle v) {
        order.assignedVehicle = v.code;
        v.order = order.orderNo;
        v.idleSince = null;
        v.parkedAt = null;
        transition(order, OrderStatus.EXECUTING, null);
        log.info("order {} assigned to {}", order.orderNo, v.code);
        if (v.status.charging() && order.type != OrderType.CHARGE) {
            // A vehicle refuses new orders while an action runs: unplug first, drive once it reports so
            try {
                fleet.stopCharging(v.code);
            } catch (RemoteCallException e) {
                log.warn("could not stop charging of {}: {}", v.code, e.getMessage());
            }
            return;
        }
        startLeg(order);
    }

    /** Starts the first leg of orders whose vehicle had to stop charging first. */
    /** Legs that could not start yet: the vehicle was charging or still finishing an earlier order. */
    private void startPendingLegs() {
        for (TransportOrder order : List.copyOf(active.values())) {
            if (order.status != OrderStatus.EXECUTING || order.routeId != null || order.leg().status() != Leg.Status.PENDING) {
                continue;
            }
            DispatchVehicle v = vehicles.get(order.assignedVehicle);
            if (v != null && v.status != null && !v.status.charging()) {
                startLeg(order);
            }
        }
    }

    private void startLeg(TransportOrder order) {
        Leg leg = order.leg();
        List<VehicleActionDto> actions = leg.action() == null ? List.of()
                : List.of(new VehicleActionDto(leg.action(), null, null, leg.parameters()));
        try {
            RouteViewDto route = traffic.requestRoute(order.assignedVehicle, leg.point(), actions);
            order.routeId = route.routeId();
            order.failingSince = null;
            order.markLeg(Leg.Status.RUNNING);
            save(order);
            if (route.status() == RouteStatus.ARRIVED) {
                legDone(order);
            }
        } catch (RemoteCallException e) {
            if (VEHICLE_BUSY.equals(e.errorCode())) {
                // it winds down an earlier order, e.g. one cancelled when this order was suspended: retried each tick
                log.info("order {} waits for {} to finish its previous order", order.orderNo, order.assignedVehicle);
                retryLeg(order);
                return;
            }
            Instant now = clock.instant();
            if (PASSING.contains(e.errorCode()) && (order.failingSince == null
                    || Duration.between(order.failingSince, now).compareTo(RETRY_PASSING_FOR) < 0)) {
                // a service restarts or is still catching up: retried each tick for a while
                log.warn("could not send vehicle {} to {} for order {}, retrying: {}", order.assignedVehicle, leg.point(),
                        order.orderNo, e.getMessage());
                order.failingSince = order.failingSince == null ? now : order.failingSince;
                retryLeg(order);
                return;
            }
            order.failingSince = null;
            suspend(order, "could not send vehicle %s to %s: %s".formatted(order.assignedVehicle, leg.point(), e.getMessage()));
        }
    }

    private void retryLeg(TransportOrder order) {
        order.routeId = null;
        order.markLeg(Leg.Status.PENDING);
        save(order);
    }

    /** Like {@link #startLeg}, but a refusal is retried on the next poll: traffic may not know the vehicle yet. */
    private void requestLegAgain(TransportOrder order) {
        Leg leg = order.leg();
        List<VehicleActionDto> actions = leg.action() == null ? List.of()
                : List.of(new VehicleActionDto(leg.action(), null, null, leg.parameters()));
        try {
            RouteViewDto route = traffic.requestRoute(order.assignedVehicle, leg.point(), actions);
            order.routeId = route.routeId();
            save(order);
            if (route.status() == RouteStatus.ARRIVED) {
                legDone(order);
            }
        } catch (RemoteCallException e) {
            log.warn("could not request the route of order {} again, retrying: {}", order.orderNo, e.getMessage());
        }
    }

    private void routeChanged(TransportOrder order, RouteStatus status, String reason) {
        if (status == RouteStatus.ARRIVED) {
            legDone(order);
        } else if (status == RouteStatus.FAILED || status == RouteStatus.CANCELLED) {
            suspend(order, "route to %s %s%s".formatted(order.leg().point(), status.name().toLowerCase(),
                    reason == null ? "" : ": " + reason));
        }
    }

    private void legDone(TransportOrder order) {
        Leg leg = order.leg();
        try {
            switch (leg.kind()) {
                case PICKUP -> resource.pickedUp(order.orderNo, order.carrierCode, order.assignedVehicle);
                case DROP -> resource.droppedOff(order.orderNo, order.carrierCode, order.targetLocation);
                default -> {
                    // MOVE and CHARGE change nothing in storage
                }
            }
        } catch (RemoteCallException e) {
            Instant now = clock.instant();
            if (PASSING.contains(e.errorCode()) && (order.failingSince == null
                    || Duration.between(order.failingSince, now).compareTo(RETRY_PASSING_FOR) < 0)) {
                // the leg stays running on its finished route: the next route poll tries again
                log.warn("storage update after {} of order {} failed, retrying: {}", leg.kind(), order.orderNo,
                        e.getMessage());
                order.failingSince = order.failingSince == null ? now : order.failingSince;
                return;
            }
            order.failingSince = null;
            suspend(order, "storage update after %s at %s failed: %s".formatted(leg.kind(), leg.point(), e.getMessage()));
            return;
        }
        order.failingSince = null;
        order.markLeg(Leg.Status.DONE);
        order.routeId = null;
        if (order.currentLeg + 1 < order.legs.size()) {
            order.currentLeg++;
            save(order);
            startLeg(order);
        } else {
            finish(order, OrderStatus.COMPLETED, null);
        }
    }

    private void suspend(TransportOrder order, String reason) {
        log.warn("order {} suspended: {}", order.orderNo, reason);
        transition(order, OrderStatus.SUSPENDED, reason);
    }

    private void finish(TransportOrder order, OrderStatus status, String reason) {
        order.finishedAt = clock.instant();
        transition(order, status, reason);
        active.remove(order.orderNo);
        if (order.assignedVehicle != null) {
            DispatchVehicle v = vehicles.get(order.assignedVehicle);
            if (v != null && order.orderNo.equals(v.order)) {
                v.order = null;
            }
        }
    }

    private void transition(TransportOrder order, OrderStatus status, String reason) {
        OrderStatus previous = order.status;
        order.status = status;
        // a transition without a reason keeps the current one, e.g. why an internal order exists
        if (reason != null) {
            order.reason = reason;
        }
        save(order);
        events.publish(OrderStatusChangedEvent.TYPE, new OrderStatusChangedEvent(order.view(), previous));
    }

    private void save(TransportOrder order) {
        order.updatedAt = clock.instant();
        orders.update(order);
    }

    private void stopRoute(TransportOrder order) {
        try {
            traffic.cancelRoute(order.assignedVehicle);
        } catch (RemoteCallException e) {
            // NO_ACTIVE_ROUTE: nothing to stop
            log.info("no route to cancel for {}: {}", order.assignedVehicle, e.getMessage());
        }
    }

    private void releaseLocks(TransportOrder order) {
        if (order.type != OrderType.TRANSPORT || order.sourceLocation == null) {
            return;
        }
        try {
            resource.release(order.orderNo, List.of(order.sourceLocation, order.targetLocation));
        } catch (RemoteCallException e) {
            log.error("could not release the locks of order {}", order.orderNo, e);
        }
    }

    // ================================================================== vehicle selection

    /** Why {@code v} cannot take {@code order} (or any work when {@code order} is null), or {@code null}. */
    private String unavailableReason(DispatchVehicle v, TransportOrder order) {
        VehicleStatusDto s = v.status;
        RegisteredVehicle r = v.registered;
        if (r == null) {
            return "not in the vehicle registry";
        }
        if (!r.enabled()) {
            return "disabled in the registry";
        }
        if (notAccepting.contains(v.code)) {
            return "not accepting orders";
        }
        if (v.connection != ConnectionStatus.ONLINE) {
            return "not online";
        }
        if (v.order != null) {
            return "busy with " + v.order;
        }
        if (!onMap(s)) {
            return "position unknown";
        }
        if (s.errors().stream().anyMatch(e -> "FATAL".equals(e.level()))) {
            return "fatal error";
        }
        if (s.battery() < r.chargeThresholdLow() || (s.charging() && s.battery() < r.chargeThresholdHigh())) {
            return "battery %.0f %%".formatted(s.battery());
        }
        if (order == null) {
            return null;
        }
        if (order.requestedVehicle != null && !order.requestedVehicle.equals(v.code) && !order.requestedVehicle.equals(r.code())) {
            return "order wants " + order.requestedVehicle;
        }
        if (!s.mapCode().equals(order.mapCode)) {
            return "on another map";
        }
        if (order.type == OrderType.TRANSPORT) {
            Set<String> allowed = compatibility.getOrDefault(order.carrierType, Set.of());
            if (!allowed.isEmpty() && !allowed.contains(r.typeCode())) {
                return "vehicle type %s cannot carry %s".formatted(r.typeCode(), order.carrierType);
            }
        }
        return null;
    }

    private static boolean onMap(VehicleStatusDto s) {
        return s.positionInitialized() && s.mapCode() != null && !s.mapCode().isEmpty()
                && s.lastNodeId() != null && !s.lastNodeId().isEmpty();
    }

    /** The candidate with the lowest travel cost to {@code point} (requirement DSP-04). */
    private Optional<DispatchVehicle> cheapest(String mapCode, List<DispatchVehicle> candidates, String point) {
        try {
            List<Double> costs = traffic.costs(new CostQueryDto(mapCode,
                            candidates.stream().map(v -> v.status.lastNodeId()).toList(), List.of(point)))
                    .seconds().stream().map(List::getFirst).toList();
            DispatchVehicle best = null;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int i = 0; i < candidates.size(); i++) {
                Double cost = costs.get(i);
                DispatchVehicle v = candidates.get(i);
                if (cost != null && (cost < bestCost || (cost == bestCost && best != null
                        && v.status.battery() > best.status.battery()))) {
                    best = v;
                    bestCost = cost;
                }
            }
            return Optional.ofNullable(best);
        } catch (RemoteCallException e) {
            log.warn("could not get travel costs: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> nearest(String mapCode, String from, List<String> points) {
        if (points.isEmpty()) {
            return Optional.empty();
        }
        try {
            List<Double> costs = traffic.costs(new CostQueryDto(mapCode, List.of(from), points)).seconds().getFirst();
            String best = null;
            double bestCost = Double.POSITIVE_INFINITY;
            for (int i = 0; i < points.size(); i++) {
                if (costs.get(i) != null && costs.get(i) < bestCost) {
                    best = points.get(i);
                    bestCost = costs.get(i);
                }
            }
            return Optional.ofNullable(best);
        } catch (RemoteCallException e) {
            log.warn("could not get travel costs: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ================================================================== registry

    private void refreshRegistryIfDue(boolean force) {
        Instant now = clock.instant();
        boolean unknown = vehicles.values().stream().anyMatch(v -> v.registered == null);
        boolean due = registryLoadedAt == null
                || Duration.between(registryLoadedAt, now).compareTo(settings.registryRefresh()) >= 0
                || (unknown && Duration.between(registryLoadedAt, now).compareTo(Duration.ofSeconds(5)) >= 0);
        if (!force && !due) {
            return;
        }
        registryLoadedAt = now;
        try {
            registry = resource.vehicles();
            compatibility = resource.carrierCompatibility();
        } catch (RemoteCallException e) {
            log.warn("could not load the vehicle registry: {}", e.getMessage());
        }
        Map<String, RegisteredVehicle> byIdentity = new HashMap<>();
        registry.forEach(r -> byIdentity.put(r.manufacturer() + "/" + r.serialNumber(), r));
        for (DispatchVehicle v : vehicles.values()) {
            v.registered = byIdentity.get(v.status.manufacturer() + "/" + v.status.serialNumber());
        }
    }

    private TransportOrder requireActive(String orderNo) {
        TransportOrder order = active.get(orderNo);
        if (order != null) {
            return order;
        }
        TransportOrder stored = orders.findByNo(orderNo).orElseThrow(() ->
                new BizException(DispatchErrorCode.ORDER_NOT_FOUND, "order %s not found".formatted(orderNo)));
        throw new BizException(DispatchErrorCode.ORDER_STATE_INVALID,
                "order %s is already %s".formatted(orderNo, stored.status));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static final class DispatchVehicle {

        final String code;
        VehicleStatusDto status;
        /** Latest of the status' connection and connection events, which arrive separately. */
        ConnectionStatus connection;
        RegisteredVehicle registered;
        /** Order the vehicle works on, or {@code null}. */
        String order;
        /** Since when the vehicle stands around with nothing to do; {@code null} while busy. */
        Instant idleSince;
        /** Parking point the vehicle was found resting on, so it is not checked again and again. */
        String parkedAt;

        DispatchVehicle(String code) {
            this.code = code;
        }
    }

    List<String> activeOrderNos() {
        return new ArrayList<>(active.keySet());
    }
}
