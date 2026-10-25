package org.fleetloom.traffic.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.fleetloom.common.error.BizException;
import org.fleetloom.common.event.EventBus;
import org.fleetloom.contracts.fleet.ConnectionStatus;
import org.fleetloom.contracts.fleet.RouteCommandDto;
import org.fleetloom.contracts.fleet.RouteEdgeDto;
import org.fleetloom.contracts.fleet.RouteNodeDto;
import org.fleetloom.contracts.fleet.VehicleActionDto;
import org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent;
import org.fleetloom.contracts.fleet.VehicleStatusDto;
import org.fleetloom.contracts.traffic.CostMatrixDto;
import org.fleetloom.contracts.traffic.CostQueryDto;
import org.fleetloom.contracts.traffic.DeadlockDetectedEvent;
import org.fleetloom.contracts.traffic.ReservationDto;
import org.fleetloom.contracts.traffic.RouteProgressedEvent;
import org.fleetloom.contracts.traffic.RouteStatus;
import org.fleetloom.contracts.traffic.RouteViewDto;
import org.fleetloom.contracts.traffic.TrafficBlockedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Route planning and traffic control (requirements TRF-01..19). A vehicle may only drive into
 * resources (points, paths, mutex areas) it holds; it gets them a few nodes ahead at a time, and gives
 * back what lies behind it. What it holds is exactly what is released to it as the VDA 5050 base.
 *
 * <p>Not thread-safe by design: every call must come from one thread, so the reservation table needs
 * no locking and behaviour is reproducible.
 */
public final class TrafficController {

    private static final Logger log = LoggerFactory.getLogger(TrafficController.class);

    /** VDA 5050 error types with which a vehicle refuses an order. */
    private static final Set<String> REJECTIONS =
            Set.of("validationError", "orderError", "noRouteError", "orderUpdateError");

    static final Duration DEADLOCK_REPEAT = Duration.ofSeconds(30);
    /** how long the vehicle types are kept, and how soon they are asked again for a vehicle not among them */
    static final Duration REGISTRY_REFRESH = Duration.ofSeconds(30);
    static final Duration REGISTRY_RETRY = Duration.ofSeconds(5);

    private final PathPlanner planner;
    private final FleetGateway fleet;
    private final MapProvider maps;
    private final VehicleRegistry registry;
    private final EventBus events;
    private TrafficSettings settings;
    private final Clock clock;

    private final Map<String, RoadGraph> graphs = new HashMap<>();
    private final Set<String> pendingMapReloads = new HashSet<>();
    private final Map<String, ReservationTable> tables = new HashMap<>();
    private final Map<String, CorridorTable> corridorTables = new HashMap<>();
    private final Map<String, VehicleTraffic> vehicles = new HashMap<>();
    private Set<String> lastDeadlock = Set.of();
    private Instant deadlockAnnouncedAt = Instant.MIN;
    /** when the current deadlock was found, or last tried to resolve */
    private Instant deadlockSince = Instant.MIN;
    private Map<String, VehicleRegistry.VehicleKind> kinds = Map.of();
    private Instant kindsLoadedAt = Instant.MIN;

    public TrafficController(PathPlanner planner, FleetGateway fleet, MapProvider maps, EventBus events,
            TrafficSettings settings, Clock clock) {
        this(planner, fleet, maps, VehicleRegistry.NONE, events, settings, clock);
    }

    public TrafficController(PathPlanner planner, FleetGateway fleet, MapProvider maps, VehicleRegistry registry,
            EventBus events, TrafficSettings settings, Clock clock) {
        this.planner = planner;
        this.fleet = fleet;
        this.maps = maps;
        this.registry = registry;
        this.events = events;
        this.settings = settings;
        this.clock = clock;
    }

    // ================================================================== inputs

    public TrafficSettings settings() {
        return settings;
    }

    /** Takes effect from the next planning and reservation step on. */
    public void updateSettings(TrafficSettings settings) {
        this.settings = settings;
    }

    public void onVehicleStatus(VehicleStatusDto status) {
        VehicleTraffic v = vehicles.computeIfAbsent(status.code(), VehicleTraffic::new);
        v.status = status;
        cancelOrphanOrder(v);
        if (status.mapCode() == null || status.mapCode().isEmpty() || !status.positionInitialized()) {
            return;
        }
        Optional<RoadGraph> graph = graphIfAvailable(status.mapCode());
        if (graph.isEmpty()) {
            return;
        }
        ActiveRoute route = v.route;
        if (route != null && route.status.isActive()) {
            followRoute(v, route, status);
        } else {
            holdWhereItStands(v, graph.get());
        }
    }

    /**
     * A vehicle on an order this controller did not give it, typically one from before a restart,
     * drives out its base (which is taken over, see holdWhereItStands) and then waits for a release
     * that never comes. Such a stuck order is cancelled, so the vehicle can take a new route.
     */
    private void cancelOrphanOrder(VehicleTraffic v) {
        VehicleStatusDto s = v.status;
        if (v.route != null || s.driving() || !hasUnfinishedOrder(s) || s.orderId().equals(v.cancelledOrphan)
                || s.connection() != ConnectionStatus.ONLINE
                || s.remainingNodes().stream().anyMatch(VehicleStatusDto.NodeRef::released)) {
            return;
        }
        log.warn("vehicle {} runs order {} unknown to traffic control; cancelling it", v.code, s.orderId());
        try {
            fleet.cancelOrder(v.code);
            v.cancelledOrphan = s.orderId();
        } catch (RuntimeException e) {
            log.warn("could not cancel order {} of vehicle {}, retrying: {}", s.orderId(), v.code, e.getMessage());
        }
    }

    private static boolean hasUnfinishedOrder(VehicleStatusDto s) {
        return s.orderId() != null && !s.orderId().isEmpty() && (!s.remainingNodes().isEmpty()
                || s.actions().stream().anyMatch(a -> !"FINISHED".equals(a.status()) && !"FAILED".equals(a.status())));
    }

    public void onConnectionChanged(VehicleConnectionChangedEvent event) {
        // Reservations of an unreachable vehicle stay: it may still be standing or rolling there (VEH-07)
        VehicleTraffic v = vehicles.get(event.vehicleCode());
        if (v != null && event.current() != ConnectionStatus.ONLINE) {
            log.warn("vehicle {} is {}; keeping its reservations", event.vehicleCode(), event.current());
        }
    }

    /**
     * The vehicle was removed from fleet control by hand, so it is gone from the site: its route ends
     * and everything it held is free for the others.
     */
    public void onVehicleForgotten(String vehicleCode) {
        VehicleTraffic v = vehicles.remove(vehicleCode);
        if (v == null) {
            return;
        }
        ActiveRoute r = v.route;
        if (r != null && r.status.isActive()) {
            r.status = RouteStatus.CANCELLED;
            r.clearBlocked();
            progressed(v, r, "vehicle removed");
        }
        corridorTables.values().forEach(t -> t.releaseAll(vehicleCode));
        for (String mapCode : List.copyOf(tables.keySet())) {
            tables.get(mapCode).releaseAll(vehicleCode);
            wakeWaiting(mapCode, vehicleCode);
        }
        log.warn("vehicle {} removed from fleet control; its reservations are released", vehicleCode);
    }

    /** A new map version was published; it is used once no route runs on the map (MAP-07). */
    public void onMapPublished(String mapCode) {
        pendingMapReloads.add(mapCode);
        reloadPendingMaps();
    }

    /** Periodic work: extend waiting routes, report blockages, try detours, detect deadlocks. */
    public void tick() {
        Instant now = clock.instant();
        for (VehicleTraffic v : vehicles.values()) {
            ActiveRoute r = v.route;
            if (r == null || !r.status.isActive() || r.orderId == null) {
                continue;
            }
            extendAndSend(v, r);
            if (!r.isStuck()) {
                continue;
            }
            if (r.waitingSince == null) {
                r.waitingSince = now;
            }
            Duration waited = Duration.between(r.waitingSince, now);
            if (!r.blockedEventSent && waited.compareTo(settings.blockedEventAfter()) >= 0) {
                r.blockedEventSent = true;
                events.publish(TrafficBlockedEvent.TYPE,
                        new TrafficBlockedEvent(v.code, r.blockedBy, r.blockedResource.toString(), r.waitingSince));
            }
            if (waited.compareTo(settings.replanAfter()) >= 0 && !isMakingProgress(r.blockedBy)) {
                tryDetour(v, r);
            }
        }
        detectDeadlocks();
        reloadPendingMaps();
    }

    // ================================================================== commands

    public RouteViewDto requestRoute(String vehicleCode, String targetPoint, List<VehicleActionDto> targetActions) {
        VehicleTraffic v = requireVehicle(vehicleCode);
        VehicleStatusDto s = v.status;
        if (s.connection() != ConnectionStatus.ONLINE) {
            throw new BizException(TrafficErrorCode.VEHICLE_NOT_ONLINE, "vehicle %s is %s".formatted(vehicleCode, s.connection()));
        }
        if (v.route != null && v.route.status.isActive()) {
            throw new BizException(TrafficErrorCode.ROUTE_ACTIVE,
                    "vehicle %s is already on route %s".formatted(vehicleCode, v.route.routeId));
        }
        if (hasUnfinishedOrder(s)) {
            // e.g. it is still braking for a cancelled route, or drives out an order from before a restart
            throw new BizException(TrafficErrorCode.VEHICLE_BUSY,
                    "vehicle %s is still finishing order %s".formatted(vehicleCode, s.orderId()));
        }
        RoadGraph graph = graph(s.mapCode());
        if (!standsOnNode(s, graph)) {
            throw new BizException(TrafficErrorCode.VEHICLE_NOT_ON_NODE,
                    "vehicle %s is not standing on a known point".formatted(vehicleCode));
        }
        if (!graph.contains(targetPoint)) {
            throw new BizException(TrafficErrorCode.UNKNOWN_POINT,
                    "point %s is not on map %s".formatted(targetPoint, graph.mapCode()));
        }
        ReservationTable table = table(graph.mapCode());
        Map<Resource, Double> penalties = new HashMap<>();
        table.snapshot().forEach((resource, holder) -> {
            if (!holder.equals(vehicleCode)) {
                penalties.put(resource, settings.occupiedPenalty());
            }
        });
        VehicleRegistry.VehicleKind kind = kind(v);
        PlannedRoute plan = planner.plan(graph, new PathPlanner.Request(s.lastNodeId(), targetPoint,
                        kind.maxSpeed(), Set.of(), penalties, flowPenalties(v, graph), kind.typeCode()))
                .orElseThrow(() -> new BizException(TrafficErrorCode.NO_ROUTE,
                        "no route from %s to %s".formatted(s.lastNodeId(), targetPoint)));

        ActiveRoute route = new ActiveRoute(UUID.randomUUID().toString(), graph.mapCode(), plan, targetActions,
                clock.instant());
        v.route = route;
        if (plan.lastIndex() == 0 && route.targetActions.isEmpty()) {
            finish(v, route, RouteStatus.ARRIVED, "already at target");
            return view(v.code, route);
        }
        extend(v, route);
        try {
            route.orderId = fleet.sendRoute(vehicleCode, command(route, 0)).orderId();
        } catch (RuntimeException e) {
            route.status = RouteStatus.FAILED;
            table.retainOnly(vehicleCode, Resource.of(graph.node(s.lastNodeId()).orElseThrow()));
            throw new BizException(TrafficErrorCode.FLEET_UNAVAILABLE,
                    "could not send route to vehicle %s: %s".formatted(vehicleCode, e.getMessage()));
        }
        route.status = route.releasedIndex > 0 ? RouteStatus.MOVING : RouteStatus.WAITING;
        progressed(v, route, null);
        return view(v.code, route);
    }

    public RouteViewDto cancelRoute(String vehicleCode) {
        VehicleTraffic v = requireVehicle(vehicleCode);
        ActiveRoute route = v.route;
        if (route == null || !route.status.isActive()) {
            throw new BizException(TrafficErrorCode.NO_ACTIVE_ROUTE, "vehicle %s has no active route".formatted(vehicleCode));
        }
        try {
            fleet.cancelOrder(vehicleCode);
        } catch (RuntimeException e) {
            throw new BizException(TrafficErrorCode.FLEET_UNAVAILABLE,
                    "could not cancel the order of vehicle %s: %s".formatted(vehicleCode, e.getMessage()));
        }
        finish(v, route, RouteStatus.CANCELLED, "cancelled");
        return view(v.code, route);
    }

    /** Drops every reservation of a vehicle, e.g. after it was pushed away by hand (TRF-19). */
    public void forceRelease(String vehicleCode) {
        VehicleTraffic v = requireVehicle(vehicleCode);
        if (v.route != null && v.route.status.isActive()) {
            throw new BizException(TrafficErrorCode.ROUTE_ACTIVE, "cancel the route of %s first".formatted(vehicleCode));
        }
        tables.values().forEach(t -> t.releaseAll(vehicleCode));
        corridorTables.values().forEach(t -> t.releaseAll(vehicleCode));
        log.warn("all reservations of vehicle {} released by hand", vehicleCode);
    }

    // ================================================================== queries

    /**
     * With {@code query.vehicles()}, row i is for that vehicle: at its type's speed and on the paths
     * open to its type. Without, rows are for a vehicle of unknown type.
     */
    public CostMatrixDto costs(CostQueryDto query) {
        RoadGraph graph = graph(query.mapCode());
        List<List<Double>> seconds = new ArrayList<>();
        for (int i = 0; i < query.from().size(); i++) {
            String from = query.from().get(i);
            VehicleTraffic v = query.vehicles() == null ? null : vehicles.get(query.vehicles().get(i));
            VehicleRegistry.VehicleKind kind = v == null || v.status == null ? unknownKind() : kind(v);
            Map<String, Double> costs = planner.costs(graph, from, query.to(), kind.maxSpeed(), kind.typeCode());
            List<Double> row = new ArrayList<>();
            for (String to : query.to()) {
                row.add(costs.get(to));
            }
            seconds.add(row);
        }
        return new CostMatrixDto(query.from(), query.to(), seconds);
    }

    public List<RouteViewDto> routes() {
        return vehicles.values().stream()
                .filter(v -> v.route != null)
                .map(v -> view(v.code, v.route))
                .sorted(Comparator.comparing(RouteViewDto::vehicleCode))
                .toList();
    }

    public Optional<RouteViewDto> route(String vehicleCode) {
        VehicleTraffic v = vehicles.get(vehicleCode);
        return v == null || v.route == null ? Optional.empty() : Optional.of(view(vehicleCode, v.route));
    }

    public List<ReservationDto> reservations() {
        List<ReservationDto> result = new ArrayList<>();
        tables.forEach((map, table) -> table.snapshot().forEach((r, holder) ->
                result.add(new ReservationDto(map, r.toString(), holder))));
        corridorTables.forEach((map, table) -> table.snapshot().forEach((corridor, holders) ->
                holders.forEach(holder -> result.add(new ReservationDto(map, Resource.corridor(corridor).toString(), holder)))));
        result.sort(Comparator.comparing(ReservationDto::mapCode).thenComparing(ReservationDto::resource));
        return result;
    }

    // ================================================================== route following

    private void followRoute(VehicleTraffic v, ActiveRoute r, VehicleStatusDto s) {
        if (r.orderId == null) {
            return;
        }
        if (rejected(s, r)) {
            String reason = s.errors().stream().filter(e -> r.orderId.equals(e.orderId()))
                    .map(e -> e.errorType() + ": " + e.description()).findFirst().orElse("rejected");
            finish(v, r, RouteStatus.FAILED, reason);
            return;
        }
        if (!r.orderId.equals(s.orderId())) {
            if (r.acknowledged) {
                // it dropped the order, e.g. it was restarted: nothing of this route will happen any more
                finish(v, r, RouteStatus.FAILED, "vehicle no longer runs order " + r.orderId);
            }
            return; // otherwise it has not taken the order over yet
        }
        r.acknowledged = true;
        int index = (int) (s.lastNodeSequenceId() / 2);
        if (index > r.passedIndex && index <= r.releasedIndex) {
            r.passedIndex = index;
        }
        if (r.passedIndex == r.plan.lastIndex() && !s.driving()) {
            Optional<String> failed = failedTargetAction(r, s);
            if (failed.isPresent()) {
                finish(v, r, RouteStatus.FAILED, failed.get());
                return;
            }
            if (targetActionsDone(r, s)) {
                finish(v, r, RouteStatus.ARRIVED, null);
                return;
            }
        }
        table(r.mapCode).retainOnly(v.code, needed(r));
        corridorTable(r.mapCode).retainOnly(v.code, neededCorridors(r));
        extendAndSend(v, r);
        wakeWaiting(r.mapCode, v.code);
    }

    /** Lets vehicles waiting on {@code mapCode} take what another vehicle just released, without waiting for the tick. */
    private void wakeWaiting(String mapCode, String releasedBy) {
        for (VehicleTraffic other : vehicles.values()) {
            ActiveRoute r = other.route;
            if (r != null && r.status.isActive() && r.orderId != null && r.mapCode.equals(mapCode)
                    && releasedBy.equals(r.blockedBy)) {
                extendAndSend(other, r);
            }
        }
    }

    /** Reserves further ahead and, if anything was gained, releases it to the vehicle. */
    private void extendAndSend(VehicleTraffic v, ActiveRoute r) {
        int before = r.releasedIndex;
        boolean reportedBlocked = r.blockedEventSent;
        extend(v, r);
        if (r.releasedIndex == before) {
            return;
        }
        try {
            fleet.extendRoute(v.code, command(r, before));
            // moving again after waiting at the start or after a reported blockage is a change worth telling
            if (r.status == RouteStatus.WAITING || reportedBlocked) {
                r.status = RouteStatus.MOVING;
                progressed(v, r, null);
            }
        } catch (RuntimeException e) {
            log.error("could not extend the route of {}; it stops at {}", v.code, r.plan.nodes().get(before), e);
            r.releasedIndex = before;
            table(r.mapCode).retainOnly(v.code, needed(r));
            corridorTable(r.mapCode).retainOnly(v.code, neededCorridors(r));
        }
    }

    private void extend(VehicleTraffic v, ActiveRoute r) {
        RoadGraph graph = graph(r.mapCode);
        ReservationTable table = table(r.mapCode);
        if (r.holdIndex >= 0 && othersAreThrough(r)) {
            r.holdIndex = -1;
        }
        int i = r.releasedIndex;
        double ahead = 0;
        for (int k = r.passedIndex; k < i; k++) {
            ahead += r.plan.edges().get(k).length();
        }
        while (i < r.plan.lastIndex() && (r.holdIndex < 0 || i < r.holdIndex)
                && (i - r.passedIndex < settings.lookaheadNodes() || ahead < settings.lookaheadDistance())) {
            RoadGraph.Edge edge = r.plan.edges().get(i);
            Set<Resource> wanted = new LinkedHashSet<>(Resource.of(edge));
            wanted.addAll(Resource.of(graph.node(edge.to()).orElseThrow()));
            List<Entry> corridorsAhead = corridorsAhead(graph, r, i);
            // the corridor first: whoever waits for one should count as waiting for it, see waiterBefore
            Optional<Map.Entry<Resource, String>> blocker = corridorBlocker(v, r, corridorsAhead)
                    .or(() -> table.blocker(v.code, wanted));
            if (blocker.isPresent()) {
                if (!blocker.get().getValue().equals(r.blockedBy)) {
                    r.waitingSince = null;
                    r.blockedEventSent = false;
                }
                r.blockedResource = blocker.get().getKey();
                r.blockedBy = blocker.get().getValue();
                r.releasedIndex = i;
                return;
            }
            table.tryReserve(v.code, wanted);
            for (Entry e : corridorsAhead) {
                corridorTable(r.mapCode).acquire(e.corridor().id(), e.direction(), v.code);
                if (e.corridor().id().equals(r.corridorWaiting)) {
                    r.corridorWaiting = null;
                }
            }
            ahead += edge.length();
            i++;
        }
        r.releasedIndex = i;
        r.clearBlocked();
    }

    // ------------------------------------------------------------------ corridors

    /** Whether the vehicles a backed-off vehicle lets pass no longer need any point of its way on. */
    private boolean othersAreThrough(ActiveRoute r) {
        return letPass(r).isEmpty();
    }

    /** Those of the vehicles a backed-off vehicle lets pass that still need a point of its way on. */
    private Set<String> letPass(ActiveRoute r) {
        Set<String> result = new HashSet<>();
        for (String code : r.holdFor) {
            VehicleTraffic o = vehicles.get(code);
            ActiveRoute other = o == null ? null : o.route;
            if (other != null && other.status.isActive() && other.plan.nodes()
                    .subList(other.passedIndex, other.plan.nodes().size()).stream().anyMatch(r.holdNodes::contains)) {
                result.add(code);
            }
        }
        return result;
    }

    /** Stands at the point it backed off to, letting others pass. */
    private static boolean isHolding(ActiveRoute r) {
        return r.holdIndex >= 0 && r.passedIndex == r.holdIndex && r.releasedIndex == r.holdIndex;
    }

    /** A corridor and the direction a route drives through it. */
    private record Entry(RoadGraph.Corridor corridor, int direction) {
    }

    /**
     * Corridors a vehicle needs before it may drive edge {@code i}: the one of that edge, and the one of
     * the edge after it. The second is taken one edge early so that a vehicle that has to wait does so
     * before the junction at the corridor's end, not on it, where it would block those coming out.
     */
    private static List<Entry> corridorsAhead(RoadGraph graph, ActiveRoute r, int i) {
        List<Entry> result = new ArrayList<>();
        for (int k = i; k <= i + 1 && k < r.plan.lastIndex(); k++) {
            RoadGraph.Edge edge = r.plan.edges().get(k);
            graph.corridor(edge.pathCode()).ifPresent(c -> {
                Entry e = new Entry(c, c.direction(edge));
                if (!result.contains(e)) {
                    result.add(e);
                }
            });
        }
        return result;
    }

    private Optional<Map.Entry<Resource, String>> corridorBlocker(VehicleTraffic v, ActiveRoute r, List<Entry> entries) {
        for (Entry e : entries) {
            Resource resource = Resource.corridor(e.corridor().id());
            Optional<String> blocker = corridorTable(r.mapCode).blocker(e.corridor().id(), e.direction(), v.code,
                    waiterBefore(v, r, e.corridor().id(), e.direction()));
            if (blocker.isPresent()) {
                if (!e.corridor().id().equals(r.corridorWaiting)) {
                    r.corridorWaiting = e.corridor().id();
                    r.corridorDirection = e.direction();
                    r.corridorWaitSince = clock.instant();
                }
                return Optional.of(Map.entry(resource, blocker.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Another vehicle that waits longer than {@code v} to drive through the corridor the other way. It
     * goes first: otherwise a steady stream in one direction could keep it waiting for ever.
     */
    private Optional<String> waiterBefore(VehicleTraffic v, ActiveRoute r, String corridor, int direction) {
        Instant since = corridor.equals(r.corridorWaiting) ? r.corridorWaitSince : clock.instant();
        Comparator<Map.Entry<Instant, String>> order = Map.Entry.<Instant, String>comparingByKey()
                .thenComparing(Map.Entry.comparingByValue());
        return vehicles.values().stream()
                .filter(o -> o != v && o.route != null && o.route.status.isActive() && o.route.mapCode.equals(r.mapCode)
                        && corridor.equals(o.route.corridorWaiting) && o.route.corridorDirection == -direction)
                .map(o -> Map.entry(o.route.corridorWaitSince, o.code))
                .filter(w -> order.compare(w, Map.entry(since, v.code)) < 0)
                .min(order)
                .map(Map.Entry::getValue);
    }

    /** Corridors the vehicle is in or about to enter: those of the edges from where it is to the end of its base. */
    private Set<String> neededCorridors(ActiveRoute r) {
        RoadGraph graph = graph(r.mapCode);
        Set<String> needed = new HashSet<>();
        for (int i = r.passedIndex; i <= r.releasedIndex && i < r.plan.lastIndex(); i++) {
            graph.corridor(r.plan.edges().get(i).pathCode()).ifPresent(c -> needed.add(c.id()));
        }
        return needed;
    }

    /**
     * Whether a vehicle is driving its route and will free what it holds by itself. Detours are only
     * worth it around vehicles that are parked, stuck or part of a deadlock.
     */
    private boolean isMakingProgress(String vehicleCode) {
        VehicleTraffic v = vehicles.get(vehicleCode);
        return v != null && v.route != null && v.route.status.isActive() && !v.route.isStuck();
    }

    /** Looks for a way around resources held by other vehicles, starting at the decision point. */
    private void tryDetour(VehicleTraffic v, ActiveRoute r) {
        RoadGraph graph = graph(r.mapCode);
        Set<Resource> occupied = occupiedByOthers(v, graph);
        String decision = r.plan.nodes().get(r.releasedIndex);
        VehicleRegistry.VehicleKind kind = kind(v);
        Optional<PlannedRoute> detour = planner.plan(graph,
                new PathPlanner.Request(decision, r.target(), kind.maxSpeed(), occupied, Map.of(),
                        flowPenalties(v, graph), kind.typeCode()));
        r.waitingSince = clock.instant();
        if (detour.isEmpty()) {
            return;
        }
        List<String> currentTail = r.plan.nodes().subList(r.releasedIndex, r.plan.nodes().size());
        if (detour.get().nodes().equals(currentTail)) {
            return;
        }
        log.info("vehicle {} takes a detour from {}: {}", v.code, decision, detour.get().nodes());
        r.plan = r.plan.splice(r.releasedIndex, detour.get());
        r.clearBlocked();
        r.corridorWaiting = null;
        try {
            fleet.extendRoute(v.code, command(r, r.releasedIndex));
        } catch (RuntimeException e) {
            log.error("could not send the detour of {}", v.code, e);
        }
        extendAndSend(v, r);
    }

    /**
     * Soft costs that keep a new route out of the way of the routes already running, in the spirit of
     * the conflict-avoidance costs of prioritized planning (Silver, "Cooperative Pathfinding", 2005) and
     * the direction-aware "highways" of Cohen, Uras and Koenig (SoCS 2015), without reserving space-time:
     * the vehicles' timing is not known well enough for that. For every other vehicle, each path still
     * ahead of it costs {@link TrafficSettings#counterflowPenalty()} against its direction and
     * {@link TrafficSettings#followPenalty()} along it. Against a vehicle's direction in a single-lane
     * corridor means anywhere in that corridor, since the corridor lock keeps the whole of it.
     */
    private Map<RoadGraph.Edge, Double> flowPenalties(VehicleTraffic v, RoadGraph graph) {
        double counterflow = settings.counterflowPenalty();
        double follow = settings.followPenalty();
        if (counterflow <= 0 && follow <= 0) {
            return Map.of();
        }
        Map<RoadGraph.Edge, Double> penalties = new HashMap<>();
        for (VehicleTraffic o : vehicles.values()) {
            ActiveRoute r = o.route;
            if (o == v || r == null || !r.status.isActive() || !r.mapCode.equals(graph.mapCode())) {
                continue;
            }
            Set<RoadGraph.Edge> against = new HashSet<>();
            Set<RoadGraph.Edge> along = new HashSet<>();
            for (RoadGraph.Edge e : r.plan.edges().subList(r.passedIndex, r.plan.edges().size())) {
                along.add(e);
                graph.edge(e.to(), e.from()).ifPresent(against::add);
                graph.corridor(e.pathCode()).ifPresent(c -> {
                    int direction = c.direction(e);
                    for (int i = 0; i < c.paths().size(); i++) {
                        // the edge of path i that runs against the vehicle's direction through the corridor
                        String from = c.nodes().get(direction > 0 ? i + 1 : i);
                        String to = c.nodes().get(direction > 0 ? i : i + 1);
                        graph.edge(from, to).ifPresent(against::add);
                    }
                });
            }
            along.forEach(e -> penalties.merge(e, follow, Double::sum));
            against.forEach(e -> penalties.merge(e, counterflow, Double::sum));
        }
        return penalties;
    }

    /** What other vehicles hold on the map, including every path of the corridors they drive through. */
    private Set<Resource> occupiedByOthers(VehicleTraffic v, RoadGraph graph) {
        Set<Resource> occupied = new HashSet<>();
        table(graph.mapCode()).snapshot().forEach((resource, holder) -> {
            if (!holder.equals(v.code)) {
                occupied.add(resource);
            }
        });
        corridorTable(graph.mapCode()).snapshot().forEach((corridor, holders) -> {
            if (!holders.contains(v.code)) {
                graph.corridors().stream().filter(c -> c.id().equals(corridor)).findFirst()
                        .ifPresent(c -> c.paths().forEach(p -> occupied.add(Resource.path(p))));
            }
        });
        return occupied;
    }

    private boolean rejected(VehicleStatusDto s, ActiveRoute r) {
        return s.errors().stream().anyMatch(e -> REJECTIONS.contains(e.errorType()) && r.orderId.equals(e.orderId()));
    }

    private static boolean targetActionsDone(ActiveRoute r, VehicleStatusDto s) {
        Set<String> finished = new HashSet<>();
        s.actions().stream().filter(a -> a.status().equals("FINISHED")).forEach(a -> finished.add(a.actionId()));
        return targetActionIds(r).stream().allMatch(finished::contains);
    }

    /** A target action the vehicle reports as FAILED, described for the route's reason. */
    private static Optional<String> failedTargetAction(ActiveRoute r, VehicleStatusDto s) {
        List<String> ids = targetActionIds(r);
        return s.actions().stream()
                .filter(a -> a.status().equals("FAILED") && ids.contains(a.actionId()))
                .map(a -> "action %s failed%s".formatted(a.actionType(),
                        a.resultDescription() == null ? "" : ": " + a.resultDescription()))
                .findFirst();
    }

    /** Ids of the target actions as the fleet service assigns them when none were given. */
    private static List<String> targetActionIds(ActiveRoute r) {
        long sequenceId = 2L * r.plan.lastIndex();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < r.targetActions.size(); i++) {
            VehicleActionDto a = r.targetActions.get(i);
            ids.add(a.actionId() != null ? a.actionId() : "%s-%d-%d".formatted(r.orderId, sequenceId, i));
        }
        return ids;
    }

    private void finish(VehicleTraffic v, ActiveRoute r, RouteStatus status, String reason) {
        r.status = status;
        r.clearBlocked();
        ReservationTable table = table(r.mapCode);
        if (status == RouteStatus.ARRIVED) {
            table.retainOnly(v.code, Resource.of(graph(r.mapCode).node(r.target()).orElseThrow()));
            corridorTable(r.mapCode).releaseAll(v.code);
        } else {
            // The vehicle may still drive to the end of what it was given (a rejected update leaves the old
            // base in force, a cancelled one still brakes): keep all of it. Once the vehicle stands on a
            // point again, holdWhereItStands narrows this down.
            table.retainOnly(v.code, needed(r));
            holdWhereItStands(v, graph(r.mapCode));
        }
        progressed(v, r, reason);
        wakeWaiting(r.mapCode, v.code);
    }

    private void progressed(VehicleTraffic v, ActiveRoute r, String reason) {
        String lastNode = r.plan.nodes().get(r.passedIndex);
        events.publish(RouteProgressedEvent.TYPE,
                new RouteProgressedEvent(r.routeId, v.code, r.target(), r.status, lastNode, reason, clock.instant()));
    }

    /** Resources the vehicle must keep: from the node it last passed to the end of its base. */
    private Set<Resource> needed(ActiveRoute r) {
        RoadGraph graph = graph(r.mapCode);
        Set<Resource> needed = new HashSet<>();
        for (int i = r.passedIndex; i <= r.releasedIndex; i++) {
            needed.addAll(Resource.of(graph.node(r.plan.nodes().get(i)).orElseThrow()));
            if (i < r.releasedIndex) {
                needed.addAll(Resource.of(r.plan.edges().get(i)));
            }
        }
        return needed;
    }

    private RouteCommandDto command(ActiveRoute r, int fromIndex) {
        RoadGraph graph = graph(r.mapCode);
        List<RouteNodeDto> nodes = new ArrayList<>();
        List<RouteEdgeDto> edges = new ArrayList<>();
        for (int i = fromIndex; i <= r.plan.lastIndex(); i++) {
            RoadGraph.Node n = graph.node(r.plan.nodes().get(i)).orElseThrow();
            List<VehicleActionDto> actions = i == r.plan.lastIndex() ? r.targetActions : List.of();
            nodes.add(new RouteNodeDto(n.code(), n.x(), n.y(), null, i <= r.releasedIndex, actions));
            if (i < r.plan.lastIndex()) {
                RoadGraph.Edge e = r.plan.edges().get(i);
                edges.add(new RouteEdgeDto(e.pathCode(), i + 1 <= r.releasedIndex, e.maxSpeed(), e.controlPoints(), List.of()));
            }
        }
        return new RouteCommandDto(r.mapCode, nodes, edges);
    }

    // ================================================================== vehicles without a route

    /**
     * A vehicle without a route keeps the point it stands on. If it is executing an order the traffic
     * service does not know (e.g. after a restart), its released nodes are taken over so that nobody
     * else is let in (NFR-03).
     */
    private void holdWhereItStands(VehicleTraffic v, RoadGraph graph) {
        VehicleStatusDto s = v.status;
        ReservationTable table = table(graph.mapCode());
        List<VehicleStatusDto.NodeRef> base = s.remainingNodes().stream().filter(VehicleStatusDto.NodeRef::released).toList();
        Set<Resource> hold = new HashSet<>();
        if (!base.isEmpty() && graph.contains(s.lastNodeId())) {
            String previous = s.lastNodeId();
            hold.addAll(Resource.of(graph.node(previous).orElseThrow()));
            for (VehicleStatusDto.NodeRef n : base) {
                graph.edge(previous, n.nodeId()).ifPresent(e -> hold.addAll(Resource.of(e)));
                graph.node(n.nodeId()).ifPresent(node -> hold.addAll(Resource.of(node)));
                previous = n.nodeId();
            }
        } else if (standsOnNode(s, graph)) {
            hold.addAll(Resource.of(graph.node(s.lastNodeId()).orElseThrow()));
        } else if (!s.driving() && graph.contains(s.lastNodeId())) {
            // Stopped between two points (e.g. after a cancel): keep the path it stands on and both ends
            Optional<RoadGraph.Edge> on = edgeUnder(graph, s);
            if (on.isEmpty()) {
                return; // position unclear: keep whatever it holds
            }
            hold.addAll(Resource.of(graph.node(on.get().from()).orElseThrow()));
            hold.addAll(Resource.of(on.get()));
            hold.addAll(Resource.of(graph.node(on.get().to()).orElseThrow()));
        } else {
            return; // still moving: keep whatever it holds until it stops
        }
        if (!s.driving()) {
            corridorTable(graph.mapCode()).releaseAll(v.code);
        }
        Set<Resource> conflicts = table.forceReserve(v.code, hold);
        if (!conflicts.isEmpty()) {
            log.error("vehicle {} occupies {} which another vehicle had reserved", v.code, conflicts);
        }
        table.retainOnly(v.code, hold);
    }

    /** The straight edge leaving the vehicle's last node that its position lies on, if any. */
    private Optional<RoadGraph.Edge> edgeUnder(RoadGraph graph, VehicleStatusDto s) {
        RoadGraph.Node from = graph.node(s.lastNodeId()).orElseThrow();
        return graph.outgoing(from.code()).stream()
                .filter(e -> e.controlPoints().isEmpty())
                .filter(e -> {
                    RoadGraph.Node to = graph.node(e.to()).orElseThrow();
                    return distanceToSegment(s.x(), s.y(), from, to) <= settings.nodeTolerance();
                })
                .findFirst();
    }

    private static double distanceToSegment(double px, double py, RoadGraph.Node a, RoadGraph.Node b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - a.x()) * dx + (py - a.y()) * dy) / lengthSquared));
        return Math.hypot(px - (a.x() + t * dx), py - (a.y() + t * dy));
    }

    private boolean standsOnNode(VehicleStatusDto s, RoadGraph graph) {
        return s.lastNodeId() != null && graph.node(s.lastNodeId())
                .map(n -> Math.hypot(n.x() - s.x(), n.y() - s.y()) <= settings.nodeTolerance())
                .orElse(false);
    }

    // ================================================================== deadlocks

    /**
     * TRF-15. A vehicle waits for the one holding what it needs next, and a vehicle that backed off out
     * of a deadlock waits for every vehicle it lets pass; vehicles on a cycle of this wait-for graph
     * (a strongly connected component of more than one, found with Tarjan's algorithm) are deadlocked.
     */
    private void detectDeadlocks() {
        Map<String, Set<String>> waitsFor = new HashMap<>();
        for (VehicleTraffic v : vehicles.values()) {
            ActiveRoute r = v.route;
            if (r == null || !r.status.isActive()) {
                continue;
            }
            if (r.isStuck()) {
                waitsFor.put(v.code, Set.of(r.blockedBy));
            } else if (isHolding(r)) {
                waitsFor.put(v.code, letPass(r));
            }
        }
        Set<String> cycle = onCycles(waitsFor);
        // letting others pass is what closes this cycle: drive on, and let the usual waiting sort it out
        for (String code : cycle.stream().sorted().toList()) {
            ActiveRoute r = vehicles.get(code).route;
            if (isHolding(r)) {
                log.warn("{} stops letting {} pass: they wait for each other", code, waitsFor.get(code));
                r.holdIndex = -1;
                extendAndSend(vehicles.get(code), r);
            }
        }
        Instant now = clock.instant();
        // a lasting deadlock is announced again now and then, for whoever missed it, e.g. a restarted service
        if (!cycle.isEmpty() && (!cycle.equals(lastDeadlock)
                || Duration.between(deadlockAnnouncedAt, now).compareTo(DEADLOCK_REPEAT) >= 0)) {
            List<String> sorted = cycle.stream().sorted().toList();
            log.warn("deadlock between {}", sorted);
            events.publish(DeadlockDetectedEvent.TYPE, new DeadlockDetectedEvent(sorted, now));
            deadlockAnnouncedAt = now;
        }
        if (!cycle.equals(lastDeadlock)) {
            deadlockSince = now;
        } else if (!cycle.isEmpty() && Duration.between(deadlockSince, now).compareTo(settings.replanAfter()) >= 0) {
            // give those who resolve deadlocks themselves (dispatch moves idle vehicles away) a chance first
            deadlockSince = now;
            resolveDeadlock(cycle);
        }
        lastDeadlock = cycle;
    }

    /** The vehicles on a cycle of {@code waitsFor}. */
    static Set<String> onCycles(Map<String, Set<String>> waitsFor) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        List<String> stack = new ArrayList<>();
        Set<String> onStack = new HashSet<>();
        Set<String> result = new HashSet<>();
        for (String v : waitsFor.keySet().stream().sorted().toList()) {
            if (!index.containsKey(v)) {
                strongConnect(v, waitsFor, index, low, stack, onStack, result);
            }
        }
        return result;
    }

    private static void strongConnect(String v, Map<String, Set<String>> waitsFor, Map<String, Integer> index,
            Map<String, Integer> low, List<String> stack, Set<String> onStack, Set<String> result) {
        index.put(v, index.size());
        low.put(v, index.get(v));
        stack.add(v);
        onStack.add(v);
        for (String w : waitsFor.getOrDefault(v, Set.of())) {
            if (!index.containsKey(w)) {
                strongConnect(w, waitsFor, index, low, stack, onStack, result);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            List<String> component = new ArrayList<>();
            String w;
            do {
                w = stack.removeLast();
                onStack.remove(w);
                component.add(w);
            } while (!w.equals(v));
            if (component.size() > 1) {
                result.addAll(component);
            }
        }
    }

    /**
     * Breaks a deadlock (TRF-16): one vehicle of the cycle backs off into the nearest point that the
     * others will not need, waits there until they are through, and then drives on to its target. The
     * one with the shortest way to such a point is chosen. A cycle where none of them can move away,
     * e.g. two vehicles swapping ends of a dead-end corridor, stays as it is and is reported.
     */
    private void resolveDeadlock(Set<String> cycle) {
        Escape best = null;
        for (String code : cycle.stream().sorted().toList()) {
            VehicleTraffic v = vehicles.get(code);
            if (v == null || v.route == null || !v.route.isStuck()) {
                continue;
            }
            Optional<Escape> escape = escape(v, cycle);
            if (escape.isPresent() && (best == null || escape.get().seconds() < best.seconds())) {
                best = escape.get();
            }
        }
        if (best == null) {
            return;
        }
        VehicleTraffic v = best.vehicle();
        ActiveRoute r = v.route;
        log.warn("resolving deadlock {}: {} backs off to {} and drives on from there", cycle.stream().sorted().toList(),
                v.code, best.route().target());
        int refuge = r.releasedIndex + best.route().lastIndex();
        r.plan = r.plan.splice(r.releasedIndex, best.route().then(best.onward()));
        r.clearBlocked();
        r.corridorWaiting = null;
        r.holdIndex = refuge;
        r.holdFor = cycle.stream().filter(c -> !c.equals(v.code)).collect(Collectors.toSet());
        r.holdNodes = Set.copyOf(best.onward().nodes());
        try {
            fleet.extendRoute(v.code, command(r, r.releasedIndex));
        } catch (RuntimeException e) {
            log.error("could not send the way out of the deadlock to {}", v.code, e);
        }
        extendAndSend(v, r);
    }

    private record Escape(VehicleTraffic vehicle, PlannedRoute route, PlannedRoute onward) {

        double seconds() {
            return route.seconds();
        }
    }

    /**
     * The nearest point {@code v} can reach without using what others hold, that is neither held by
     * nor ahead of the other vehicles in the cycle, with the way there and the way from there on.
     */
    private Optional<Escape> escape(VehicleTraffic v, Set<String> cycle) {
        ActiveRoute r = v.route;
        RoadGraph graph = graph(r.mapCode);
        Set<Resource> occupied = occupiedByOthers(v, graph);
        // it cannot back up through a corridor where others follow it
        corridorTable(r.mapCode).snapshot().forEach((corridor, holders) -> {
            if (holders.contains(v.code) && holders.size() > 1) {
                graph.corridors().stream().filter(c -> c.id().equals(corridor)).findFirst()
                        .ifPresent(c -> c.paths().forEach(p -> occupied.add(Resource.path(p))));
            }
        });
        Set<String> needed = new HashSet<>();
        for (String other : cycle) {
            VehicleTraffic o = vehicles.get(other);
            if (o != null && o != v && o.route != null) {
                needed.addAll(o.route.plan.nodes().subList(o.route.passedIndex, o.route.plan.nodes().size()));
            }
        }
        String here = r.plan.nodes().get(r.releasedIndex);
        VehicleRegistry.VehicleKind kind = kind(v);
        // breadth first over the free part of the map, nearest points first
        List<String> queue = new ArrayList<>(List.of(here));
        Set<String> seen = new HashSet<>(queue);
        for (int k = 0; k < queue.size(); k++) {
            for (RoadGraph.Edge e : graph.outgoing(queue.get(k))) {
                RoadGraph.Node next = graph.node(e.to()).orElseThrow();
                if (!e.allows(kind.typeCode()) || !seen.add(e.to()) || Resource.of(e).stream().anyMatch(occupied::contains)
                        || Resource.of(next).stream().anyMatch(occupied::contains)) {
                    continue;
                }
                if (!needed.contains(e.to())) {
                    Optional<PlannedRoute> away = planner.plan(graph,
                            new PathPlanner.Request(here, e.to(), kind.maxSpeed(), occupied, Map.of(), Map.of(),
                                    kind.typeCode()));
                    Optional<PlannedRoute> onward = planner.plan(graph, new PathPlanner.Request(e.to(), r.target(),
                            kind.maxSpeed(), Set.of(), Map.of(), Map.of(), kind.typeCode()));
                    if (away.isPresent() && onward.isPresent()) {
                        return Optional.of(new Escape(v, away.get(), onward.get()));
                    }
                }
                queue.add(e.to());
            }
        }
        return Optional.empty();
    }

    // ================================================================== vehicle types

    /** Type and speed of the vehicle as registered; the default speed and no type if it is not. */
    private VehicleRegistry.VehicleKind kind(VehicleTraffic v) {
        String identity = v.status.manufacturer() + "/" + v.status.serialNumber();
        Instant now = clock.instant();
        Duration age = Duration.between(kindsLoadedAt, now);
        if (age.compareTo(REGISTRY_REFRESH) >= 0 || (!kinds.containsKey(identity) && age.compareTo(REGISTRY_RETRY) >= 0)) {
            kindsLoadedAt = now;
            try {
                kinds = Map.copyOf(registry.vehicles());
            } catch (RuntimeException e) {
                log.warn("vehicle registry is not available, keeping the vehicle types known: {}", e.getMessage());
            }
        }
        VehicleRegistry.VehicleKind kind = kinds.get(identity);
        return kind == null ? unknownKind() : kind;
    }

    private VehicleRegistry.VehicleKind unknownKind() {
        return new VehicleRegistry.VehicleKind(null, settings.defaultSpeed());
    }

    // ================================================================== maps

    private RoadGraph graph(String mapCode) {
        return graphIfAvailable(mapCode).orElseThrow(() -> new BizException(TrafficErrorCode.MAP_UNAVAILABLE,
                "map %s is not available".formatted(mapCode)));
    }

    private Optional<RoadGraph> graphIfAvailable(String mapCode) {
        RoadGraph graph = graphs.get(mapCode);
        if (graph == null) {
            try {
                graph = RoadGraphBuilder.build(maps.activeTopology(mapCode));
                graphs.put(mapCode, graph);
                log.info("loaded map {} version {}", mapCode, graph.version());
            } catch (RuntimeException e) {
                log.warn("map {} is not available: {}", mapCode, e.getMessage());
                return Optional.empty();
            }
        }
        return Optional.of(graph);
    }

    private void reloadPendingMaps() {
        for (String mapCode : Set.copyOf(pendingMapReloads)) {
            boolean busy = vehicles.values().stream().anyMatch(v -> v.route != null && v.route.status.isActive()
                    && v.route.mapCode.equals(mapCode));
            if (!busy) {
                pendingMapReloads.remove(mapCode);
                graphs.remove(mapCode);
                graphIfAvailable(mapCode);
            }
        }
    }

    private ReservationTable table(String mapCode) {
        return tables.computeIfAbsent(mapCode, k -> new ReservationTable());
    }

    private CorridorTable corridorTable(String mapCode) {
        return corridorTables.computeIfAbsent(mapCode, k -> new CorridorTable());
    }

    private VehicleTraffic requireVehicle(String code) {
        VehicleTraffic v = vehicles.get(code);
        if (v == null || v.status == null) {
            throw new BizException(TrafficErrorCode.VEHICLE_UNKNOWN, "vehicle %s is unknown".formatted(code));
        }
        return v;
    }

    private RouteViewDto view(String vehicleCode, ActiveRoute r) {
        return new RouteViewDto(r.routeId, vehicleCode, r.mapCode, r.target(), r.status, r.plan.nodes(), r.passedIndex,
                r.releasedIndex, r.blockedBy, r.blockedResource == null ? null : r.blockedResource.toString(),
                r.plan.seconds(), r.createdAt);
    }

    private static final class VehicleTraffic {

        final String code;
        VehicleStatusDto status;
        ActiveRoute route;
        /** an order of unknown origin this controller already asked the vehicle to cancel */
        String cancelledOrphan;

        VehicleTraffic(String code) {
            this.code = code;
        }
    }
}
