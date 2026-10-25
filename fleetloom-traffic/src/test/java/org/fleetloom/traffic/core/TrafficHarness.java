package org.fleetloom.traffic.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.fleetloom.common.event.InMemoryEventBus;
import org.fleetloom.contracts.fleet.OrderRefDto;
import org.fleetloom.contracts.fleet.RouteCommandDto;
import org.fleetloom.contracts.fleet.VehicleForgottenEvent;
import org.fleetloom.contracts.fleet.VehicleStateUpdatedEvent;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.fleetloom.contracts.traffic.DeadlockDetectedEvent;
import org.fleetloom.contracts.traffic.RouteProgressedEvent;
import org.fleetloom.contracts.traffic.TrafficBlockedEvent;
import org.fleetloom.fleet.app.FleetService;
import org.fleetloom.simulator.core.Pose;
import org.fleetloom.simulator.core.SimulatedAgv;
import org.fleetloom.simulator.core.SimulationEngine;
import org.fleetloom.simulator.core.SiteMap;
import org.fleetloom.simulator.core.VehicleProfile;
import org.fleetloom.simulator.core.VirtualVehicle;
import org.fleetloom.simulator.platform.SiteMaps;
import org.fleetloom.vda5050.client.AgvClient;
import org.fleetloom.vda5050.client.MasterClient;
import org.fleetloom.vda5050.transport.inmemory.InMemoryBroker;

/**
 * Traffic controller + real fleet service + simulated vehicles, wired in one process over an
 * in-memory MQTT broker and event bus. Time is simulated; after every step the distance between every
 * pair of vehicles is recorded, so tests can assert that vehicles never came too close.
 */
public final class TrafficHarness {

    static final double DT = 0.05;
    static final VehicleProfile PROFILE = new VehicleProfile(1.0, 0.5, 0.5, Math.PI / 2, 0.1, 0.0, 0.0, 1.0, 1.0,
            Map.of("pick", 2.0), Set.of());

    private final InMemoryBroker broker = new InMemoryBroker();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00Z"));
    private final InMemoryEventBus events = new InMemoryEventBus("test", clock);
    private final SimulationEngine engine = new SimulationEngine(50, 1.0);
    private final SiteMap siteMap;
    private final FleetService fleet;
    private final TrafficController traffic;
    private MapTopologyDto topology;
    private double sinceTick;
    private double minDistance = Double.MAX_VALUE;

    public final List<RouteProgressedEvent> progress = new CopyOnWriteArrayList<>();
    public final List<DeadlockDetectedEvent> deadlocks = new CopyOnWriteArrayList<>();
    public final List<TrafficBlockedEvent> blocked = new CopyOnWriteArrayList<>();

    public TrafficHarness(MapTopologyDto topology) {
        this(topology, TrafficSettings.defaults());
    }

    public TrafficHarness(MapTopologyDto topology, TrafficSettings settings) {
        this(topology, settings, VehicleRegistry.NONE);
    }

    public TrafficHarness(MapTopologyDto topology, TrafficSettings settings, VehicleRegistry registry) {
        this.topology = topology;
        this.siteMap = SiteMaps.from(topology);
        this.fleet = new FleetService(new MasterClient(broker.newClient(), "uagv", clock), events, s -> { }, clock,
                Duration.ofSeconds(5));
        FleetGateway gateway = new FleetGateway() {
            @Override
            public OrderRefDto sendRoute(String vehicleCode, RouteCommandDto route) {
                return fleet.sendRoute(vehicleCode, route);
            }

            @Override
            public OrderRefDto extendRoute(String vehicleCode, RouteCommandDto route) {
                return fleet.extendRoute(vehicleCode, route);
            }

            @Override
            public void cancelOrder(String vehicleCode) {
                fleet.cancelOrder(vehicleCode);
            }
        };
        Supplier<MapTopologyDto> currentMap = () -> this.topology;
        this.traffic = new TrafficController(new AStarPlanner(settings.turnPenaltyPerRadian()), gateway,
                mapCode -> currentMap.get(), registry, events, settings, clock);
        events.subscribe(VehicleStateUpdatedEvent.TYPE, "traffic", VehicleStateUpdatedEvent.class,
                e -> traffic.onVehicleStatus(e.payload().status()));
        events.subscribe(VehicleForgottenEvent.TYPE, "traffic", VehicleForgottenEvent.class,
                e -> traffic.onVehicleForgotten(e.payload().vehicleCode()));
        events.subscribe(RouteProgressedEvent.TYPE, "test", RouteProgressedEvent.class, e -> progress.add(e.payload()));
        events.subscribe(DeadlockDetectedEvent.TYPE, "test", DeadlockDetectedEvent.class, e -> deadlocks.add(e.payload()));
        events.subscribe(TrafficBlockedEvent.TYPE, "test", TrafficBlockedEvent.class, e -> blocked.add(e.payload()));
        fleet.start();
    }

    /** Places a vehicle on a map point. */
    public TrafficHarness vehicle(String code, String point) {
        SiteMap.Point p = siteMap.point(point).orElseThrow();
        VirtualVehicle vehicle = new VirtualVehicle(code, PROFILE, siteMap, new Pose(p.x(), p.y(), 0), point);
        SimulatedAgv agv = new SimulatedAgv(vehicle,
                new AgvClient(broker.newClient(), "uagv", "FLEETLOOM-SIM", code, clock), 1.0, 0.1);
        agv.connect();
        engine.add(agv);
        return this;
    }

    /** Restarts a vehicle: it comes back on the point it last passed, without an order. */
    public void restartVehicle(String code) {
        String point = vehicle(code).lastNodeId();
        engine.remove(code);
        vehicle(code, point);
    }

    /** Takes a vehicle off the site and removes it from fleet control, as an operator would. */
    public void removeVehicle(String code) {
        engine.remove(code);
        fleet.forget(code);
    }

    public TrafficController traffic() {
        return traffic;
    }

    public FleetService fleet() {
        return fleet;
    }

    public Clock clock() {
        return clock;
    }

    /** Replaces the map the provider hands out, as if a new version had been published. */
    public void publishMap(MapTopologyDto newTopology) {
        topology = newTopology;
        traffic.onMapPublished(newTopology.mapCode());
    }

    public void run(double seconds) {
        for (int i = 0; i < Math.round(seconds / DT); i++) {
            step();
        }
    }

    public boolean runUntil(double maxSeconds, BooleanSupplier condition) {
        for (int i = 0; i < Math.round(maxSeconds / DT); i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            step();
        }
        return condition.getAsBoolean();
    }

    private void step() {
        engine.step(DT);
        clock.advance(Duration.ofMillis(50));
        sinceTick += DT;
        if (sinceTick >= 0.5 - 1e-9) {
            traffic.tick();
            sinceTick = 0;
        }
        List<SimulatedAgv> agvs = engine.agvs();
        for (int i = 0; i < agvs.size(); i++) {
            for (int j = i + 1; j < agvs.size(); j++) {
                Pose a = agvs.get(i).vehicle().pose();
                Pose b = agvs.get(j).vehicle().pose();
                minDistance = Math.min(minDistance, Math.hypot(a.x() - b.x(), a.y() - b.y()));
            }
        }
    }

    /** Smallest distance between any two vehicles seen so far, in meters. */
    public double minDistance() {
        return minDistance;
    }

    public Pose pose(String code) {
        return engine.find(code).orElseThrow().vehicle().pose();
    }

    public VirtualVehicle vehicle(String code) {
        return engine.find(code).orElseThrow().vehicle();
    }

    public boolean arrived(String code) {
        return progress.stream().anyMatch(p -> p.vehicleCode().equals(code)
                && p.status() == org.fleetloom.contracts.traffic.RouteStatus.ARRIVED);
    }

    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
