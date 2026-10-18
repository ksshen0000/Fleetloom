package org.rcs3000.fleet.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rcs3000.common.error.BizException;
import org.rcs3000.common.event.InMemoryEventBus;
import org.rcs3000.contracts.fleet.ConnectionStatus;
import org.rcs3000.contracts.fleet.OrderRefDto;
import org.rcs3000.contracts.fleet.VehicleActionDto;
import org.rcs3000.contracts.fleet.VehicleConnectionChangedEvent;
import org.rcs3000.contracts.fleet.VehicleStateUpdatedEvent;
import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.fleet.core.FleetErrorCode;
import org.rcs3000.fleet.core.Routes;
import org.rcs3000.simulator.core.Pose;
import org.rcs3000.simulator.core.SimulatedAgv;
import org.rcs3000.simulator.core.SimulationEngine;
import org.rcs3000.simulator.core.SiteMap;
import org.rcs3000.simulator.core.VehicleProfile;
import org.rcs3000.simulator.core.VirtualVehicle;
import org.rcs3000.vda5050.client.AgvClient;
import org.rcs3000.vda5050.client.MasterClient;
import org.rcs3000.vda5050.transport.inmemory.InMemoryBroker;

/**
 * The fleet service driving real simulated vehicles over an in-memory MQTT broker. Simulated time is
 * advanced explicitly, so every scenario is deterministic.
 */
class FleetServiceTest {

    private static final String MAP = "WH1";
    private static final VehicleProfile PROFILE = new VehicleProfile(1.0, 0.5, 0.5, Math.PI / 2, 0.1, 0.01, 0.0, 1.0,
            1.0, Map.of("pick", 2.0), Set.of());

    private final InMemoryBroker broker = new InMemoryBroker();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00Z"));
    private final InMemoryEventBus events = new InMemoryEventBus("rcs-fleet", clock);
    private final List<VehicleConnectionChangedEvent> connectionEvents = new CopyOnWriteArrayList<>();
    private final List<VehicleStateUpdatedEvent> stateEvents = new CopyOnWriteArrayList<>();
    private final Map<String, VehicleStatusDto> cache = new ConcurrentHashMap<>();
    private final SimulationEngine engine = new SimulationEngine(50, 1.0);
    private FleetService fleet;

    @BeforeEach
    void setUp() {
        events.subscribe(VehicleConnectionChangedEvent.TYPE, "test", VehicleConnectionChangedEvent.class,
                e -> connectionEvents.add(e.payload()));
        events.subscribe(VehicleStateUpdatedEvent.TYPE, "test", VehicleStateUpdatedEvent.class,
                e -> stateEvents.add(e.payload()));
        fleet = new FleetService(new MasterClient(broker.newClient(), "uagv", clock), events,
                s -> cache.put(s.code(), s), clock, Duration.ofSeconds(5));
        fleet.start();
        addVehicle("AGV-1", new Pose(0, 0, 0));
        addVehicle("AGV-2", new Pose(0, 10, 0));
        simulate(0.1);
    }

    private void addVehicle(String serial, Pose pose) {
        VirtualVehicle vehicle = new VirtualVehicle(serial, PROFILE, SiteMap.empty(MAP), pose, "");
        SimulatedAgv agv = new SimulatedAgv(vehicle,
                new AgvClient(broker.newClient(), "uagv", "RCS3000-SIM", serial, clock), 1.0, 0.1);
        agv.connect();
        engine.add(agv);
    }

    private void simulate(double seconds) {
        for (int i = 0; i < Math.round(seconds / 0.05); i++) {
            engine.step(0.05);
            clock.advance(Duration.ofMillis(50));
        }
    }

    private boolean simulateUntil(double maxSeconds, BooleanSupplier condition) {
        for (int i = 0; i < Math.round(maxSeconds / 0.05); i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            simulate(0.05);
        }
        return condition.getAsBoolean();
    }

    private VehicleStatusDto status(String code) {
        return fleet.vehicle(code);
    }

    @Test
    void discoversVehiclesFromTheirMessages() {
        assertThat(fleet.vehicles()).extracting(VehicleStatusDto::code).containsExactly("AGV-1", "AGV-2");
        assertThat(status("AGV-2").connection()).isEqualTo(ConnectionStatus.ONLINE);
        assertThat(status("AGV-2").y()).isEqualTo(10.0);
        assertThat(connectionEvents).extracting(VehicleConnectionChangedEvent::vehicleCode).containsExactly("AGV-1", "AGV-2");
        assertThat(fleet.factsheet("AGV-1").physicalParameters().speedMax()).isEqualTo(1.0);
        assertThat(cache).containsKeys("AGV-1", "AGV-2");
    }

    @Test
    void routeIsDrivenAndProgressIsReported() {
        OrderRefDto ref = fleet.sendRoute("AGV-1", Routes.on(MAP).node("A", 0, 0).node("B", 5, 0).node("C", 10, 0).build());

        assertThat(simulateUntil(30, () -> "B".equals(status("AGV-1").lastNodeId()))).isTrue();
        assertThat(status("AGV-1").remainingNodes()).extracting(VehicleStatusDto.NodeRef::nodeId).containsExactly("C");

        assertThat(simulateUntil(30, () -> "C".equals(status("AGV-1").lastNodeId()) && !status("AGV-1").driving())).isTrue();
        VehicleStatusDto done = status("AGV-1");
        assertThat(done.orderId()).isEqualTo(ref.orderId());
        assertThat(done.x()).isEqualTo(10.0);
        assertThat(done.errors()).isEmpty();
        assertThat(stateEvents).anySatisfy(e -> assertThat(e.status().lastNodeId()).isEqualTo("B"));
        assertThat(status("AGV-2").x()).isZero();
    }

    @Test
    void extendingTheRouteKeepsTheVehicleMoving() {
        fleet.sendRoute("AGV-1", Routes.on(MAP).node("A", 0, 0).node("B", 10, 0).horizon().node("C", 20, 0).build());
        simulate(3);

        OrderRefDto ref = fleet.extendRoute("AGV-1", Routes.on(MAP).node("B", 10, 0).node("C", 20, 0).build());
        double minSpeed = Double.MAX_VALUE;
        for (int i = 0; i < 200; i++) {
            simulate(0.05);
            minSpeed = Math.min(minSpeed, status("AGV-1").velocity());
        }

        assertThat(ref.orderUpdateId()).isEqualTo(1);
        assertThat(minSpeed).isGreaterThan(0.9);
        assertThat(simulateUntil(30, () -> "C".equals(status("AGV-1").lastNodeId()))).isTrue();
        assertThat(status("AGV-1").orderUpdateId()).isEqualTo(1);
        assertThat(status("AGV-1").errors()).isEmpty();
    }

    @Test
    void vehicleRejectionShowsUpInTheStatus() {
        fleet.sendRoute("AGV-1", Routes.on(MAP).node("X", 50, 50).node("Y", 60, 50).build());
        simulate(0.1);

        assertThat(status("AGV-1").errors()).singleElement()
                .satisfies(e -> assertThat(e.errorType()).isEqualTo("noRouteError"));
    }

    @Test
    void nodeActionsAreExecuted() {
        fleet.sendRoute("AGV-1", Routes.on(MAP).node("A", 0, 0)
                .node("B", 5, 0, new VehicleActionDto("pick", null, null, Map.of("loadId", "PALLET-1"))).build());

        assertThat(simulateUntil(30, () -> status("AGV-1").loadIds().contains("PALLET-1"))).isTrue();
        assertThat(status("AGV-1").actions()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo("FINISHED"));
    }

    @Test
    void pauseAndResume() {
        fleet.sendRoute("AGV-1", Routes.on(MAP).node("A", 0, 0).node("B", 20, 0).build());
        simulate(5);

        fleet.pause("AGV-1");
        simulate(4);
        double x = status("AGV-1").x();
        simulate(2);
        assertThat(status("AGV-1").paused()).isTrue();
        assertThat(status("AGV-1").x()).isEqualTo(x);

        fleet.resume("AGV-1");
        assertThat(simulateUntil(40, () -> "B".equals(status("AGV-1").lastNodeId()))).isTrue();
    }

    @Test
    void cancelStopsTheVehicleAndClearsTheRoute() {
        fleet.sendRoute("AGV-1", Routes.on(MAP).node("A", 0, 0).node("B", 20, 0).build());
        simulate(5);

        String actionId = fleet.cancelOrder("AGV-1");

        assertThat(simulateUntil(5, () -> status("AGV-1").actions().stream()
                .anyMatch(a -> a.actionId().equals(actionId) && a.status().equals("FINISHED")))).isTrue();
        assertThat(status("AGV-1").remainingNodes()).isEmpty();
        assertThat(status("AGV-1").driving()).isFalse();
        assertThat(status("AGV-1").x()).isBetween(1.0, 19.0);
    }

    @Test
    void relocateSetsThePosition() {
        fleet.relocate("AGV-2", 3, 4, Math.PI, MAP, "P7");
        simulate(0.1);

        VehicleStatusDto s = status("AGV-2");
        assertThat(s.x()).isEqualTo(3.0);
        assertThat(s.y()).isEqualTo(4.0);
        assertThat(s.lastNodeId()).isEqualTo("P7");
        assertThat(s.mapCode()).isEqualTo(MAP);
    }

    @Test
    void silentVehicleIsMarkedBrokenAndRecovers() {
        clock.advance(Duration.ofSeconds(6));
        fleet.checkHeartbeats();

        assertThat(status("AGV-1").connection()).isEqualTo(ConnectionStatus.CONNECTION_BROKEN);
        assertThat(connectionEvents.getLast().reason()).contains("no state");
        assertError(() -> fleet.pause("AGV-1"), FleetErrorCode.VEHICLE_NOT_ONLINE);

        // the next periodic state (at most one second away) brings it back
        assertThat(simulateUntil(1.1, () -> status("AGV-1").connection() == ConnectionStatus.ONLINE)).isTrue();
    }

    @Test
    void vehicleThatSignedOffCannotBeCommanded() {
        engine.remove("AGV-2");

        assertThat(status("AGV-2").connection()).isEqualTo(ConnectionStatus.OFFLINE);
        assertError(() -> fleet.sendRoute("AGV-2", Routes.on(MAP).node("A", 0, 10).build()),
                FleetErrorCode.VEHICLE_NOT_ONLINE);
    }

    @Test
    void unknownVehicleIsNotFound() {
        assertError(() -> fleet.vehicle("NOPE"), FleetErrorCode.VEHICLE_NOT_FOUND);
    }

    private static void assertError(ThrowingCallable call, FleetErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
