package org.rcs3000.fleet.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.rcs3000.contracts.fleet.ConnectionStatus;
import org.rcs3000.contracts.fleet.RouteCommandDto;
import org.rcs3000.contracts.fleet.VehicleConnectionChangedEvent;
import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.vda5050.client.VehicleId;
import org.rcs3000.vda5050.v2.AgvPosition;
import org.rcs3000.vda5050.v2.Connection;
import org.rcs3000.vda5050.v2.Order;
import org.rcs3000.vda5050.v2.State;
import org.rcs3000.vda5050.v2.Velocity;
import org.rcs3000.vda5050.v2.factsheet.Factsheet;

/**
 * What the fleet service knows about one vehicle: connection, last state, factsheet and the orders it
 * sent. Thread-safe: MQTT callbacks, HTTP commands and the heartbeat check all go through it.
 */
public final class VehicleSession {

    private final String code;
    private final VehicleId id;
    private final OrderTracker orders = new OrderTracker();
    private ConnectionStatus connection;
    private Instant lastSeen;
    private Instant lastState;
    private State state;
    private Factsheet factsheet;

    public VehicleSession(String code, VehicleId id) {
        this.code = code;
        this.id = id;
    }

    public String code() {
        return code;
    }

    public VehicleId id() {
        return id;
    }

    /** Applies a connection message; returns the change event if the status changed. */
    public synchronized Optional<VehicleConnectionChangedEvent> onConnection(Connection message, Instant now) {
        lastSeen = now;
        ConnectionStatus next = switch (message.connectionState()) {
            case ONLINE -> ConnectionStatus.ONLINE;
            case OFFLINE -> ConnectionStatus.OFFLINE;
            case CONNECTIONBROKEN -> ConnectionStatus.CONNECTION_BROKEN;
        };
        if (next == ConnectionStatus.ONLINE) {
            // Count the announcement as a heartbeat so the timeout starts from now
            lastState = now;
        }
        return change(next, "connection message " + message.connectionState(), now);
    }

    /** Applies a state message; a vehicle that sends state is online. */
    public synchronized Optional<VehicleConnectionChangedEvent> onState(State message, Instant now) {
        state = message;
        lastSeen = now;
        lastState = now;
        return change(ConnectionStatus.ONLINE, "state received", now);
    }

    public synchronized void onFactsheet(Factsheet message, Instant now) {
        factsheet = message;
        lastSeen = now;
    }

    /** Marks an online vehicle as broken if it sent no state within {@code timeout}. */
    public synchronized Optional<VehicleConnectionChangedEvent> checkHeartbeat(Instant now, Duration timeout) {
        if (connection == ConnectionStatus.ONLINE && lastState != null && lastState.plus(timeout).isBefore(now)) {
            return change(ConnectionStatus.CONNECTION_BROKEN,
                    "no state for more than " + timeout.toSeconds() + " s", now);
        }
        return Optional.empty();
    }

    public synchronized Order newOrder(String orderId, RouteCommandDto command) {
        return orders.newOrder(orderId, command);
    }

    public synchronized Order updateOrder(RouteCommandDto command) {
        return orders.update(command);
    }

    public synchronized ConnectionStatus connection() {
        return connection;
    }

    public synchronized Optional<Factsheet> factsheet() {
        return Optional.ofNullable(factsheet);
    }

    public synchronized long sentOrderUpdateId() {
        return orders.orderUpdateId();
    }

    public synchronized VehicleStatusDto status() {
        State s = state;
        AgvPosition p = s == null ? null : s.agvPosition();
        Velocity v = s == null ? null : s.velocity();
        double speed = v == null ? 0 : Math.hypot(nz(v.vx()), nz(v.vy()));
        return new VehicleStatusDto(code, id.manufacturer(), id.serialNumber(), connection, lastSeen,
                p == null ? null : p.mapId(),
                p == null ? 0 : p.x(), p == null ? 0 : p.y(), p == null ? 0 : p.theta(),
                p != null && p.positionInitialized(),
                speed,
                s != null && s.driving(),
                s != null && Boolean.TRUE.equals(s.paused()),
                s == null || s.operatingMode() == null ? null : s.operatingMode().name(),
                s == null ? null : s.orderId(),
                s == null ? 0 : s.orderUpdateId(),
                s == null ? null : s.lastNodeId(),
                s == null ? 0 : s.lastNodeSequenceId(),
                s == null ? List.of() : s.nodeStates().stream()
                        .map(n -> new VehicleStatusDto.NodeRef(n.nodeId(), n.sequenceId(), n.released())).toList(),
                s == null ? 0 : s.batteryState().batteryCharge(),
                s != null && s.batteryState().charging(),
                s == null || s.loads() == null ? List.of() : s.loads().stream().map(l -> l.loadId()).toList(),
                s == null ? List.of() : s.actionStates().stream()
                        .map(a -> new VehicleStatusDto.ActionStatus(a.actionId(), a.actionType(),
                                a.actionStatus().name(), a.resultDescription()))
                        .toList(),
                s == null ? List.of() : s.errors().stream()
                        .map(e -> new VehicleStatusDto.VehicleError(e.errorType(), e.errorLevel().name(),
                                e.errorDescription()))
                        .toList());
    }

    private Optional<VehicleConnectionChangedEvent> change(ConnectionStatus next, String reason, Instant now) {
        if (next == connection) {
            return Optional.empty();
        }
        ConnectionStatus previous = connection;
        connection = next;
        return Optional.of(new VehicleConnectionChangedEvent(code, previous, next, reason, now));
    }

    private static double nz(Double value) {
        return value == null ? 0 : value;
    }
}
