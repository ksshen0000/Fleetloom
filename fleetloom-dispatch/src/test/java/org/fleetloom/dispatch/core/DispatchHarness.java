package org.fleetloom.dispatch.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.fleetloom.common.error.BizException;
import org.fleetloom.common.event.InMemoryEventBus;
import org.fleetloom.contracts.dispatch.OrderStatus;
import org.fleetloom.contracts.fleet.OrderRefDto;
import org.fleetloom.contracts.fleet.RouteCommandDto;
import org.fleetloom.contracts.fleet.VehicleActionDto;
import org.fleetloom.contracts.fleet.VehicleStateUpdatedEvent;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.fleetloom.contracts.map.PathDirection;
import org.fleetloom.contracts.map.PathDto;
import org.fleetloom.contracts.map.PathShape;
import org.fleetloom.contracts.map.PointDto;
import org.fleetloom.contracts.map.PointType;
import org.fleetloom.contracts.map.VehicleOrientation;
import org.fleetloom.contracts.storage.TransportLockDto;
import org.fleetloom.contracts.traffic.CostMatrixDto;
import org.fleetloom.contracts.traffic.CostQueryDto;
import org.fleetloom.contracts.traffic.RouteProgressedEvent;
import org.fleetloom.contracts.traffic.RouteViewDto;
import org.fleetloom.fleet.app.FleetService;
import org.fleetloom.simulator.core.Pose;
import org.fleetloom.simulator.core.SimulatedAgv;
import org.fleetloom.simulator.core.SimulationEngine;
import org.fleetloom.simulator.core.SiteMap;
import org.fleetloom.simulator.core.VehicleProfile;
import org.fleetloom.simulator.core.VirtualVehicle;
import org.fleetloom.simulator.platform.SiteMaps;
import org.fleetloom.traffic.core.AStarPlanner;
import org.fleetloom.traffic.core.FleetGateway;
import org.fleetloom.traffic.core.TrafficController;
import org.fleetloom.traffic.core.TrafficSettings;
import org.fleetloom.vda5050.client.AgvClient;
import org.fleetloom.vda5050.client.MasterClient;
import org.fleetloom.vda5050.transport.inmemory.InMemoryBroker;

/**
 * The dispatcher with the real traffic controller, fleet service and simulated vehicles in one process.
 * The storage side is {@link FakeResource}, which follows fleetloom-resource's locking rules. Events reach the
 * dispatcher after each simulation step, as they would between separate services.
 */
public final class DispatchHarness {

    public static final String MAP = "DEMO";
    static final double DT = 0.05;
    /** 1 m/s, charging +1 %/s, draining 0.5 % per meter so batteries visibly go down. */
    static final VehicleProfile PROFILE = new VehicleProfile(1.0, 0.5, 0.5, Math.PI / 2, 0.1, 0.5, 0.0, 1.0, 1.0,
            Map.of("pick", 2.0, "drop", 2.0), Set.of());

    private final InMemoryBroker broker = new InMemoryBroker();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00Z"));
    private final InMemoryEventBus events = new InMemoryEventBus("test", clock);
    private final SimulationEngine engine = new SimulationEngine(50, 1.0);
    private final MapTopologyDto topology;
    private final SiteMap siteMap;
    private final FleetService fleet;
    private TrafficController traffic;
    private final Queue<Runnable> dispatcherInbox = new ArrayDeque<>();
    public final FakeResource resource;
    public final InMemoryOrders orders = new InMemoryOrders();
    public final InMemorySettings settings = new InMemorySettings();
    public final InMemoryTemplates templates = new InMemoryTemplates();
    private Dispatcher dispatcher;
    private double sinceTrafficTick;
    private double sinceDispatchTick;
    private double minDistance = Double.MAX_VALUE;

    public DispatchHarness() {
        this(false);
    }

    /** @param parkingPoint whether the map has a parking point PK1 right of P02, at (12, 0) */
    public DispatchHarness(boolean parkingPoint) {
        this.topology = gridWithCharger(parkingPoint);
        this.siteMap = SiteMaps.from(topology);
        this.resource = new FakeResource(topology);
        this.fleet = new FleetService(new MasterClient(broker.newClient(), "uagv", clock), events, s -> { }, clock,
                Duration.ofSeconds(5));
        this.traffic = newTraffic();
        events.subscribe(VehicleStateUpdatedEvent.TYPE, "traffic", VehicleStateUpdatedEvent.class,
                e -> traffic.onVehicleStatus(e.payload().status()));
        events.subscribe(VehicleStateUpdatedEvent.TYPE, "dispatch", VehicleStateUpdatedEvent.class,
                e -> dispatcherInbox.add(() -> dispatcher.onVehicleStatus(e.payload().status())));
        events.subscribe(org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent.TYPE, "dispatch",
                org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent.class,
                e -> dispatcherInbox.add(() -> dispatcher.onConnectionChanged(e.payload())));
        events.subscribe(org.fleetloom.contracts.traffic.TrafficBlockedEvent.TYPE, "dispatch",
                org.fleetloom.contracts.traffic.TrafficBlockedEvent.class,
                e -> dispatcherInbox.add(() -> dispatcher.onTrafficBlocked(e.payload())));
        events.subscribe(RouteProgressedEvent.TYPE, "dispatch", RouteProgressedEvent.class,
                e -> dispatcherInbox.add(() -> dispatcher.onRouteProgressed(e.payload())));
        events.subscribe(org.fleetloom.contracts.traffic.DeadlockDetectedEvent.TYPE, "dispatch",
                org.fleetloom.contracts.traffic.DeadlockDetectedEvent.class,
                e -> dispatcherInbox.add(() -> dispatcher.onDeadlock(e.payload().vehicleCodes())));
        fleet.start();
        dispatcher = newDispatcher();
        dispatcher.start();
    }

    /** While set, route requests fail as if fleetloom-traffic did not answer. */
    public boolean trafficDown;

    /** Traffic control as after a restart: no routes, reservations rebuilt from the vehicle states. */
    public void restartTraffic() {
        traffic = newTraffic();
    }

    private TrafficController newTraffic() {
        return new TrafficController(new AStarPlanner(2.0), new FleetGateway() {
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
        }, mapCode -> topology, events, TrafficSettings.defaults(), clock);
    }

    /** A dispatcher on the same orders, settings and services, e.g. to simulate a restart. */
    public Dispatcher newDispatcher() {
        return new Dispatcher(orders, templates, settings, resource, trafficGateway(), vehicle -> fleet.stopCharging(vehicle),
                events, DispatchSettings.defaults(), clock);
    }

    public void restartDispatcher() {
        dispatcherInbox.clear();
        dispatcher = newDispatcher();
        dispatcher.start();
    }

    private Ports.TrafficGateway trafficGateway() {
        return new Ports.TrafficGateway() {
            @Override
            public RouteViewDto requestRoute(String vehicleCode, String point, List<VehicleActionDto> targetActions) {
                if (trafficDown) {
                    throw new RemoteCallException("UNAVAILABLE", "fleetloom-traffic is unreachable", null);
                }
                return remote(() -> traffic.requestRoute(vehicleCode, point, targetActions));
            }

            @Override
            public void cancelRoute(String vehicleCode) {
                remote(() -> traffic.cancelRoute(vehicleCode));
            }

            @Override
            public Optional<RouteViewDto> route(String vehicleCode) {
                return traffic.route(vehicleCode);
            }

            @Override
            public CostMatrixDto costs(CostQueryDto query) {
                return remote(() -> traffic.costs(query));
            }
        };
    }

    private static <T> T remote(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (BizException e) {
            throw new RemoteCallException(e.getErrorCode().code(), e.getMessage(), e);
        }
    }

    /** Places a vehicle on a point and registers it, like a vehicle in fleetloom-resource's registry. */
    public DispatchHarness vehicle(String code, String point) {
        SiteMap.Point p = siteMap.point(point).orElseThrow();
        VirtualVehicle vehicle = new VirtualVehicle(code, PROFILE, siteMap, new Pose(p.x(), p.y(), 0), point);
        SimulatedAgv agv = new SimulatedAgv(vehicle, new AgvClient(broker.newClient(), "uagv", "FLEETLOOM-SIM", code, clock),
                1.0, 0.1);
        agv.connect();
        engine.add(agv);
        resource.registry.add(new Ports.RegisteredVehicle(code, "JACK", "FLEETLOOM-SIM", code, true, 20, 90));
        return this;
    }

    public InMemoryEventBus events() {
        return events;
    }

    public java.time.Clock clock() {
        return clock;
    }

    public Dispatcher dispatcher() {
        return dispatcher;
    }

    public VirtualVehicle simulated(String code) {
        return engine.find(code).orElseThrow().vehicle();
    }

    /** Puts a stopped vehicle back on a point by hand, as an operator would. */
    public void dispatcherRelocate(String code, String point) {
        SiteMap.Point p = siteMap.point(point).orElseThrow();
        fleet.relocate(code, p.x(), p.y(), 0, MAP, point);
        run(0.2);
    }

    public void removeVehicle(String code) {
        engine.remove(code);
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

    public boolean orderReaches(String orderNo, OrderStatus status, double maxSeconds) {
        return runUntil(maxSeconds, () -> dispatcher.get(orderNo).status() == status);
    }

    private void step() {
        engine.step(DT);
        clock.advance(Duration.ofMillis(50));
        sinceTrafficTick += DT;
        sinceDispatchTick += DT;
        if (sinceTrafficTick >= 0.5 - 1e-9) {
            traffic.tick();
            sinceTrafficTick = 0;
        }
        for (Runnable r; (r = dispatcherInbox.poll()) != null; ) {
            r.run();
        }
        if (sinceDispatchTick >= 1.0 - 1e-9) {
            dispatcher.tick();
            sinceDispatchTick = 0;
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

    public double minDistance() {
        return minDistance;
    }

    /**
     * 3 × 3 grid of points 4 m apart, P00 at (0, 0), P22 at (8, 8), and a charging point CH1 at (-4, 0)
     * next to P00.
     */
    static MapTopologyDto gridWithCharger(boolean parkingPoint) {
        List<PointDto> points = new ArrayList<>();
        List<PathDto> paths = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                points.add(new PointDto("P" + r + c, c * 4.0, r * 4.0, null, PointType.NORMAL, Map.of()));
                if (c < 2) {
                    paths.add(path("P" + r + c, "P" + r + (c + 1)));
                }
                if (r < 2) {
                    paths.add(path("P" + r + c, "P" + (r + 1) + c));
                }
            }
        }
        points.add(new PointDto("CH1", -4, 0, null, PointType.CHARGING, Map.of()));
        paths.add(path("CH1", "P00"));
        if (parkingPoint) {
            points.add(new PointDto("PK1", 12, 0, null, PointType.PARKING, Map.of()));
            paths.add(path("P02", "PK1"));
        }
        return new MapTopologyDto(MAP, 1, points, paths, List.of());
    }

    private static PathDto path(String a, String b) {
        return new PathDto(a + "-" + b, a, b, PathDirection.BIDIRECTIONAL, PathShape.LINE, List.of(), null,
                VehicleOrientation.ANY, null, 1.0, true, 4.0);
    }

    // ================================================================== fakes

    /** fleetloom-resource's storage rules in memory: one carrier per location, owner-checked locks. */
    public static final class FakeResource implements Ports.ResourceGateway {

        final List<Ports.RegisteredVehicle> registry = new ArrayList<>();
        final Map<String, Set<String>> compatibility = new HashMap<>();
        private final MapTopologyDto topology;
        private final Map<String, Loc> locations = new LinkedHashMap<>();
        private final Map<String, Carrier> carriers = new HashMap<>();

        FakeResource(MapTopologyDto topology) {
            this.topology = topology;
        }

        /** Adds an empty location served from {@code point}. */
        public void location(String code, String zone, String point) {
            locations.put(code, new Loc(code, zone, point));
        }

        /** Adds a carrier standing in {@code location}. */
        public void carrier(String code, String type, String location) {
            carriers.put(code, new Carrier(code, type, "STORAGE", location));
            locations.get(location).carrier = code;
        }

        public String locationOf(String carrier) {
            Carrier c = carriers.get(carrier);
            return c.locationType + ":" + c.ref;
        }

        public String status(String location) {
            Loc l = locations.get(location);
            return l.owner == null ? (l.carrier == null ? "EMPTY" : "OCCUPIED") : (l.inbound ? "LOCKED_IN" : "LOCKED_OUT");
        }

        @Override
        public Optional<Ports.CarrierInfo> carrier(String code) {
            return Optional.ofNullable(carriers.get(code)).map(c -> new Ports.CarrierInfo(c.code, c.type, c.locationType, c.ref));
        }

        @Override
        public Optional<Ports.LocationInfo> location(String code) {
            return Optional.ofNullable(locations.get(code)).map(l -> new Ports.LocationInfo(l.code, MAP, l.point));
        }

        @Override
        public TransportLockDto lock(String owner, String carrierCode, String targetLocation) {
            Carrier c = carriers.get(carrierCode);
            Loc source = locations.get(c.ref);
            Loc target = locations.get(targetLocation);
            if (target == null) {
                throw new RemoteCallException("LOCATION_NOT_FOUND", "no " + targetLocation, null);
            }
            if (source.owner != null || target.owner != null || target.carrier != null) {
                throw new RemoteCallException("LOCATION_STATE_INVALID", "location busy", null);
            }
            source.owner = owner;
            source.inbound = false;
            target.owner = owner;
            target.inbound = true;
            return new TransportLockDto(owner, carrierCode, c.type, source.code, MAP, source.point, target.code, MAP, target.point);
        }

        /** While set, storage updates fail as if fleetloom-resource did not answer. */
        public boolean down;

        @Override
        public void pickedUp(String owner, String carrierCode, String vehicleCode) {
            if (down) {
                throw new RemoteCallException("UNAVAILABLE", "fleetloom-resource is unreachable", null);
            }
            Carrier c = carriers.get(carrierCode);
            Loc source = locations.get(c.ref);
            requireOwner(source, owner);
            source.owner = null;
            source.carrier = null;
            c.locationType = "VEHICLE";
            c.ref = vehicleCode;
        }

        @Override
        public void droppedOff(String owner, String carrierCode, String targetLocation) {
            Loc target = locations.get(targetLocation);
            requireOwner(target, owner);
            target.owner = null;
            target.carrier = carrierCode;
            Carrier c = carriers.get(carrierCode);
            c.locationType = "STORAGE";
            c.ref = targetLocation;
        }

        @Override
        public void release(String owner, List<String> codes) {
            codes.stream().map(locations::get).filter(l -> owner.equals(l.owner)).forEach(l -> l.owner = null);
        }

        @Override
        public List<String> availableLocations(String zone, String carrierType, String mapCode, String nearPoint) {
            PointDto from = topology.points().stream().filter(p -> p.code().equals(nearPoint)).findFirst().orElse(null);
            return locations.values().stream()
                    .filter(l -> l.zone.equals(zone) && l.owner == null && l.carrier == null)
                    .sorted(java.util.Comparator.comparingDouble(l -> from == null ? 0 : distance(from, l.point)))
                    .map(l -> l.code)
                    .toList();
        }

        @Override
        public List<Ports.RegisteredVehicle> vehicles() {
            return List.copyOf(registry);
        }

        @Override
        public Map<String, Set<String>> carrierCompatibility() {
            return Map.copyOf(compatibility);
        }

        @Override
        public List<String> waitingPoints(String mapCode) {
            return topology.points().stream().filter(p -> p.type() == PointType.NORMAL).map(PointDto::code).toList();
        }

        @Override
        public List<String> parkingPoints(String mapCode) {
            return topology.points().stream().filter(p -> p.type() == PointType.PARKING).map(PointDto::code).toList();
        }

        @Override
        public List<String> chargingPoints(String mapCode) {
            return topology.points().stream().filter(p -> p.type() == PointType.CHARGING).map(PointDto::code).toList();
        }

        private double distance(PointDto from, String point) {
            PointDto to = topology.points().stream().filter(p -> p.code().equals(point)).findFirst().orElseThrow();
            return Math.hypot(to.x() - from.x(), to.y() - from.y());
        }

        private static void requireOwner(Loc l, String owner) {
            if (!owner.equals(l.owner)) {
                throw new RemoteCallException("LOCK_OWNER_MISMATCH", l.code + " is locked by " + l.owner, null);
            }
        }

        private static final class Loc {
            final String code;
            final String zone;
            final String point;
            String carrier;
            String owner;
            boolean inbound;

            Loc(String code, String zone, String point) {
                this.code = code;
                this.zone = zone;
                this.point = point;
            }
        }

        private static final class Carrier {
            final String code;
            final String type;
            String locationType;
            String ref;

            Carrier(String code, String type, String locationType, String ref) {
                this.code = code;
                this.type = type;
                this.locationType = locationType;
                this.ref = ref;
            }
        }
    }

    /** Keeps copies, like a database, so tests notice when the dispatcher forgets to save. */
    public static final class InMemoryOrders implements Ports.OrderRepository {

        private final Map<String, TransportOrder> rows = new LinkedHashMap<>();
        private long ids;

        @Override
        public void insert(TransportOrder order) {
            order.assignId(++ids);
            rows.put(order.orderNo(), copy(order));
        }

        @Override
        public void update(TransportOrder order) {
            TransportOrder stored = rows.get(order.orderNo());
            if (stored.version() != order.version()) {
                throw new BizException(DispatchErrorCode.ORDER_CONCURRENT_MODIFICATION, "stale");
            }
            order.versionStored(order.version() + 1);
            rows.put(order.orderNo(), copy(order));
        }

        @Override
        public Optional<TransportOrder> findByNo(String orderNo) {
            return Optional.ofNullable(rows.get(orderNo)).map(InMemoryOrders::copy);
        }

        @Override
        public Optional<TransportOrder> findByExternalNo(String externalNo) {
            return rows.values().stream().filter(o -> externalNo.equals(o.externalNo())).findFirst().map(InMemoryOrders::copy);
        }

        @Override
        public List<TransportOrder> findActive() {
            return rows.values().stream().filter(o -> !o.status().isFinal()).map(InMemoryOrders::copy).toList();
        }

        @Override
        public List<TransportOrder> find(OrderStatus status, int limit) {
            List<TransportOrder> all = new ArrayList<>(rows.values());
            java.util.Collections.reverse(all);
            return all.stream().filter(o -> status == null || o.status() == status).limit(limit).map(InMemoryOrders::copy).toList();
        }

        static TransportOrder copy(TransportOrder o) {
            return new TransportOrder(o.id(), o.orderNo(), o.externalNo(), o.type(), o.priority(), o.carrierCode(),
                    o.carrierType(), o.sourceLocation(), o.targetLocation(), o.mapCode(), o.requestedVehicle(),
                    o.assignedVehicle(), o.status(), o.currentLeg(), o.legs(), o.reason(), o.routeId(), o.createdAt(),
                    o.updatedAt(), o.finishedAt(), o.version()).withDeadline(o.deadline());
        }
    }

    public static final class InMemorySettings implements Ports.VehicleSettingsRepository {

        private final Set<String> off = new HashSet<>();

        @Override
        public Set<String> notAccepting() {
            return Set.copyOf(off);
        }

        @Override
        public void setAccepting(String vehicleCode, boolean accepting) {
            if (accepting) {
                off.remove(vehicleCode);
            } else {
                off.add(vehicleCode);
            }
        }
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

    public static final class InMemoryTemplates implements Ports.TemplateRepository {

        private final Map<String, OrderTemplate> rows = new java.util.TreeMap<>();

        @Override
        public Optional<OrderTemplate> find(String code) {
            return Optional.ofNullable(rows.get(code));
        }

        @Override
        public List<OrderTemplate> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public void save(OrderTemplate template) {
            rows.put(template.code(), template);
        }

        @Override
        public boolean delete(String code) {
            return rows.remove(code) != null;
        }
    }
}
