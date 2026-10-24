package org.rcs3000.traffic.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.rcs3000.common.error.BizException;
import org.rcs3000.contracts.fleet.VehicleActionDto;
import org.rcs3000.contracts.map.AreaType;
import org.rcs3000.contracts.map.MapTopologyDto;
import org.rcs3000.contracts.map.PathDirection;
import org.rcs3000.contracts.map.PathDto;
import org.rcs3000.contracts.map.PathShape;
import org.rcs3000.contracts.map.VehicleOrientation;
import org.rcs3000.contracts.traffic.CostMatrixDto;
import org.rcs3000.contracts.traffic.CostQueryDto;
import org.rcs3000.contracts.traffic.ReservationDto;
import org.rcs3000.contracts.traffic.RouteStatus;
import org.rcs3000.contracts.traffic.RouteViewDto;
import org.rcs3000.simulator.core.Pose;

/**
 * Traffic control end to end: the controller plans and reserves, the real fleet service sends VDA 5050
 * orders, simulated vehicles drive them. Grid points are 4 m apart; vehicles must never come closer
 * than 2 m to each other.
 */
class TrafficControllerTest {

    private static final double SAFE_DISTANCE = 2.0;

    private static List<String> reservedBy(TrafficHarness h, String vehicle) {
        return h.traffic().reservations().stream().filter(r -> r.vehicleCode().equals(vehicle))
                .map(ReservationDto::resource).sorted().toList();
    }

    @Test
    void singleVehicleReachesItsTargetAndKeepsOnlyThatPoint() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(3, 3, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);

        RouteViewDto route = h.traffic().requestRoute("AGV-1", "P22", null);

        assertThat(route.status()).isEqualTo(RouteStatus.MOVING);
        assertThat(route.nodes()).first().isEqualTo("P00");
        assertThat(h.runUntil(60, () -> h.arrived("AGV-1"))).isTrue();
        Pose p = h.pose("AGV-1");
        assertThat(p.x()).isEqualTo(8.0);
        assertThat(p.y()).isEqualTo(8.0);
        assertThat(reservedBy(h, "AGV-1")).containsExactly("NODE:P22");
    }

    @Test
    void reservesAheadAndReleasesBehind() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 6, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);
        h.traffic().requestRoute("AGV-1", "P05", null);

        // 3 nodes / 5 m lookahead with 4 m edges: two edges ahead at the start
        // the row is a single-lane corridor: it is taken as a whole, in the vehicle's direction
        assertThat(reservedBy(h, "AGV-1")).containsExactly("CORRIDOR:P00~P05", "NODE:P00", "NODE:P01", "NODE:P02",
                "NODE:P03", "PATH:P00-P01", "PATH:P01-P02", "PATH:P02-P03");
        h.runUntil(60, () -> h.vehicle("AGV-1").lastNodeId().equals("P02"));
        h.run(0.1);

        assertThat(reservedBy(h, "AGV-1")).doesNotContain("NODE:P00", "NODE:P01", "PATH:P00-P01").contains("NODE:P02");
        assertThat(h.runUntil(60, () -> h.arrived("AGV-1"))).isTrue();
    }

    @Test
    void vehiclesCrossingAtAJunctionNeverMeet() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(3, 3, 4)).vehicle("AGV-1", "P10").vehicle("AGV-2", "P01");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "P12", null);
        h.traffic().requestRoute("AGV-2", "P21", null);

        assertThat(h.runUntil(90, () -> h.arrived("AGV-1") && h.arrived("AGV-2"))).isTrue();
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
    }

    @Test
    void oncomingVehiclesOnAGridGoAroundEachOther() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(3, 3, 4)).vehicle("AGV-1", "P10").vehicle("AGV-2", "P12");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "P12", null);
        h.traffic().requestRoute("AGV-2", "P10", null);

        assertThat(h.runUntil(120, () -> h.arrived("AGV-1") && h.arrived("AGV-2"))).isTrue();
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
    }

    @Test
    void parkedVehicleIsDrivenAround() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(3, 3, 4)).vehicle("AGV-1", "P10").vehicle("PARKED", "P11");
        h.run(0.2);

        RouteViewDto route = h.traffic().requestRoute("AGV-1", "P12", null);

        assertThat(route.nodes()).doesNotContain("P11");
        assertThat(h.runUntil(90, () -> h.arrived("AGV-1"))).isTrue();
        assertThat(h.pose("PARKED").x()).isEqualTo(4.0);
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
    }

    @Test
    void waitsBehindAVehicleUntilTheWayIsFree() {
        // No detours: the vehicle behind has to wait until the one in front moves out of the way
        TrafficSettings patient = new TrafficSettings(3, 5.0, 2.0, 1.0, 0.0, 0.1,
                java.time.Duration.ofSeconds(5), java.time.Duration.ofHours(1));
        TrafficHarness h = new TrafficHarness(TestMaps.grid(2, 5, 4), patient).vehicle("FRONT", "P02").vehicle("BACK", "P00");
        h.run(0.2);

        RouteViewDto planned = h.traffic().requestRoute("BACK", "P04", null);
        assertThat(planned.nodes()).contains("P02");
        h.run(15);
        assertThat(h.pose("BACK").x()).isLessThanOrEqualTo(4.0);
        assertThat(h.traffic().route("BACK").orElseThrow().blockedBy()).isEqualTo("FRONT");
        assertThat(h.blocked).anySatisfy(b -> assertThat(b.blockedBy()).isEqualTo("FRONT"));

        h.traffic().requestRoute("FRONT", "P12", null);

        assertThat(h.runUntil(60, () -> h.arrived("BACK"))).isTrue();
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
        // the end of the reported blockage is announced, so alarms about it can be cleared
        assertThat(h.progress).anySatisfy(p -> {
            assertThat(p.vehicleCode()).isEqualTo("BACK");
            assertThat(p.status()).isEqualTo(RouteStatus.MOVING);
            assertThat(p.at()).isAfter(h.blocked.getFirst().waitingSince());
        });
    }

    @Test
    void aRemovedVehicleNoLongerBlocksTheWay() {
        TrafficSettings patient = new TrafficSettings(3, 5.0, 2.0, 1.0, 0.0, 0.1,
                java.time.Duration.ofSeconds(5), java.time.Duration.ofHours(1));
        TrafficHarness h = new TrafficHarness(TestMaps.grid(2, 5, 4), patient).vehicle("GONE", "P02").vehicle("BACK", "P00");
        h.run(0.2);
        h.traffic().requestRoute("BACK", "P04", null);
        h.run(10);
        assertThat(h.traffic().route("BACK").orElseThrow().blockedBy()).isEqualTo("GONE");

        h.removeVehicle("GONE");

        assertThat(h.runUntil(60, () -> h.arrived("BACK"))).isTrue();
        assertThat(h.traffic().reservations()).noneSatisfy(r -> assertThat(r.vehicleCode()).isEqualTo("GONE"));
    }

    @Test
    void doesNotDetourAroundAVehicleThatIsDrivingThrough() {
        // AGV-1 first turns around on the spot (4 s), so AGV-2 waits longer than the 2 s detour threshold
        TrafficSettings impatient = new TrafficSettings(3, 5.0, 2.0, 1.0, 30.0, 0.1,
                java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(2));
        TrafficHarness h = new TrafficHarness(TestMaps.grid(3, 3, 4), impatient).vehicle("AGV-1", "P12").vehicle("AGV-2", "P21");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "P10", null);
        h.traffic().requestRoute("AGV-2", "P01", null);

        assertThat(h.runUntil(90, () -> h.arrived("AGV-1") && h.arrived("AGV-2"))).isTrue();
        assertThat(h.traffic().route("AGV-2").orElseThrow().nodes()).containsExactly("P21", "P11", "P01");
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
    }

    @Test
    void headOnInACorridorIsReportedAsDeadlock() {
        TrafficHarness h = new TrafficHarness(TestMaps.corridor()).vehicle("AGV-1", "A").vehicle("AGV-2", "C");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "C", null);
        h.traffic().requestRoute("AGV-2", "A", null);
        h.run(30);

        assertThat(h.deadlocks).singleElement()
                .satisfies(d -> assertThat(d.vehicleCodes()).containsExactly("AGV-1", "AGV-2"));
        assertThat(h.arrived("AGV-1")).isFalse();
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);

        // while it lasts it is announced again, for services that missed it
        h.run(TrafficController.DEADLOCK_REPEAT.toSeconds());
        assertThat(h.deadlocks).hasSize(2);
    }

    @Test
    void aDeadlockIsResolvedByOneVehicleBackingOffIntoASideBranch() {
        // AGV-2 stands at the dead end D: it can only wait until AGV-1 is out of its way
        TrafficHarness h = new TrafficHarness(TestMaps.roadWithSideBranch()).vehicle("AGV-1", "A").vehicle("AGV-2", "D");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "D", null);
        h.run(1);
        h.traffic().requestRoute("AGV-2", "A", null);
        assertThat(h.runUntil(30, () -> !h.deadlocks.isEmpty())).isTrue();

        // AGV-1 backs off into S, lets AGV-2 pass and then drives on to D
        assertThat(h.runUntil(120, () -> h.arrived("AGV-1") && h.arrived("AGV-2"))).isTrue();
        assertThat(h.traffic().route("AGV-1").orElseThrow().nodes()).contains("S");
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
    }

    @Test
    void vehiclesMeetingAtACorridorTakeTurnsInsteadOfMeetingHeadOn() {
        TrafficHarness h = new TrafficHarness(TestMaps.corridorBetweenJunctions())
                .vehicle("AGV-1", "W1").vehicle("AGV-2", "E2");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "E1", null);
        h.traffic().requestRoute("AGV-2", "W2", null);
        h.run(1);
        // the second one waits before the junction, out of the way of the first coming out
        assertThat(h.traffic().route("AGV-2").orElseThrow().blockedResource()).isEqualTo("CORRIDOR:A~D");
        assertThat(reservedBy(h, "AGV-2")).doesNotContain("NODE:D");

        assertThat(h.runUntil(120, () -> h.arrived("AGV-1") && h.arrived("AGV-2"))).isTrue();
        assertThat(h.deadlocks).isEmpty();
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
    }

    @Test
    void vehiclesFollowEachOtherThroughACorridor() {
        TrafficHarness h = new TrafficHarness(TestMaps.corridorBetweenJunctions())
                .vehicle("AGV-1", "W1").vehicle("AGV-2", "W2");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "E1", null);
        h.traffic().requestRoute("AGV-2", "E2", null);
        boolean shared = false;
        for (int i = 0; i < 2400 && !(h.arrived("AGV-1") && h.arrived("AGV-2")); i++) {
            h.run(0.05);
            shared |= reservedBy(h, "AGV-1").contains("CORRIDOR:A~D") && reservedBy(h, "AGV-2").contains("CORRIDOR:A~D");
        }

        assertThat(h.arrived("AGV-1") && h.arrived("AGV-2")).isTrue();
        assertThat(shared).isTrue();
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
        assertThat(h.traffic().reservations()).noneMatch(r -> r.resource().startsWith("CORRIDOR"));
    }

    @Test
    void aStreamOneWayDoesNotKeepAVehicleWaitingTheOtherWayForEver() {
        TrafficHarness h = new TrafficHarness(TestMaps.corridorBetweenJunctions())
                .vehicle("AGV-1", "W1").vehicle("AGV-2", "W2").vehicle("AGV-3", "E2");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "E1", null);
        h.runUntil(30, () -> h.vehicle("AGV-1").lastNodeId().equals("B"));
        h.traffic().requestRoute("AGV-3", "W1", null);
        h.run(0.5);
        // AGV-2 could follow AGV-1 in, but AGV-3 waits longer to come the other way
        h.traffic().requestRoute("AGV-2", "E2", null);
        h.run(1);
        assertThat(h.traffic().route("AGV-2").orElseThrow().blockedResource()).isEqualTo("CORRIDOR:A~D");

        // AGV-3 needs W1, which AGV-1 leaves; AGV-2 needs E2, which AGV-3 leaves
        assertThat(h.runUntil(180, () -> h.arrived("AGV-1") && h.arrived("AGV-2") && h.arrived("AGV-3"))).isTrue();
        assertThat(h.minDistance()).isGreaterThan(SAFE_DISTANCE);
    }

    @Test
    void mutexAreaIsEnteredByOneVehicleAtATime() {
        // The area covers P01 and P11; the two vehicles drive along rows 0 and 1 and share no point or path
        MapTopologyDto map = TestMaps.grid(2, 3, 4, List.of(TestMaps.rectangle("Z", AreaType.MUTEX, 3, -1, 5, 5)));
        TrafficHarness h = new TrafficHarness(map).vehicle("AGV-1", "P00").vehicle("AGV-2", "P10");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "P02", null);
        h.traffic().requestRoute("AGV-2", "P12", null);
        boolean bothInside = false;
        for (int i = 0; i < 1200 && !(h.arrived("AGV-1") && h.arrived("AGV-2")); i++) {
            h.run(0.05);
            bothInside |= inside(h.pose("AGV-1")) && inside(h.pose("AGV-2"));
        }

        assertThat(h.arrived("AGV-1") && h.arrived("AGV-2")).isTrue();
        assertThat(bothInside).isFalse();
    }

    private static boolean inside(Pose p) {
        return p.x() > 3 && p.x() < 5;
    }

    @Test
    void routeEndsOnlyAfterTheTargetActionsFinished() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 3, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);

        h.traffic().requestRoute("AGV-1", "P02",
                List.of(new VehicleActionDto("pick", null, null, Map.of("loadId", "PALLET-1"))));

        assertThat(h.runUntil(60, () -> h.vehicle("AGV-1").lastNodeId().equals("P02") && !h.vehicle("AGV-1").isDriving())).isTrue();
        h.run(0.5);
        assertThat(h.arrived("AGV-1")).isFalse();
        assertThat(h.runUntil(5, () -> h.arrived("AGV-1"))).isTrue();
        assertThat(h.vehicle("AGV-1").loads()).singleElement().satisfies(l -> assertThat(l.loadId()).isEqualTo("PALLET-1"));
    }

    @Test
    void failedTargetActionFailsTheRoute() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 3, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);

        // P02 is no charging point, so the simulated vehicle refuses to start charging there
        h.traffic().requestRoute("AGV-1", "P02", List.of(new VehicleActionDto("startCharging", null, null, null)));

        assertThat(h.runUntil(60, () -> !h.progress.isEmpty() && h.progress.getLast().status() == RouteStatus.FAILED)).isTrue();
        assertThat(h.progress.getLast().reason()).contains("startCharging").contains("not on a charging point");
        assertThat(h.arrived("AGV-1")).isFalse();
    }

    @Test
    void cancelStopsTheVehicleAndFreesTheRestOfTheRoute() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 6, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);
        h.traffic().requestRoute("AGV-1", "P05", null);
        h.run(6);

        RouteViewDto cancelled = h.traffic().cancelRoute("AGV-1");
        h.run(5);

        assertThat(cancelled.status()).isEqualTo(RouteStatus.CANCELLED);
        assertThat(h.vehicle("AGV-1").isDriving()).isFalse();
        // it was between P01 (4 m) and P02 (8 m), finished that path and stopped on P02, which it alone holds now
        assertThat(h.pose("AGV-1").x()).isEqualTo(8.0);
        assertThat(reservedBy(h, "AGV-1")).containsExactly("NODE:P02");
        assertThat(h.progress.getLast().status()).isEqualTo(RouteStatus.CANCELLED);
    }

    @Test
    void rejectedOrderFailsTheRoute() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 3, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);

        // The simulated vehicle does not know this action type and refuses the whole order
        h.traffic().requestRoute("AGV-1", "P02", List.of(new VehicleActionDto("teleport", null, null, null)));
        h.run(1);

        assertThat(h.progress.getLast().status()).isEqualTo(RouteStatus.FAILED);
        assertThat(h.progress.getLast().reason()).contains("orderError");
        assertThat(h.pose("AGV-1").x()).isZero();
        assertThat(reservedBy(h, "AGV-1")).containsExactly("NODE:P00");
    }

    @Test
    void takesOverTheBaseOfAnOrderItDidNotSend() {
        // Simulates a restart of the traffic service: the vehicle is driving an order the controller never saw
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 5, 4)).vehicle("AGV-1", "P00").vehicle("AGV-2", "P04");
        h.run(0.2);
        h.fleet().sendRoute("AGV-1", Routes.straight("P00", "P01", "P02", "P03"));
        h.run(1);

        assertThat(reservedBy(h, "AGV-1")).contains("NODE:P02", "NODE:P03", "PATH:P02-P03");
        RouteViewDto other = h.traffic().requestRoute("AGV-2", "P02", null);
        assertThat(other.status()).isEqualTo(RouteStatus.WAITING);
        assertThat(other.blockedBy()).isEqualTo("AGV-1");
    }

    @Test
    void aForeignOrderStuckAtTheEndOfItsBaseIsCancelledSoTheVehicleCanGoOn() {
        // after a restart: the vehicle drove out the base of an old order and waits for a release that never comes
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 5, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);
        h.fleet().sendRoute("AGV-1", Routes.withHorizon(2, "P00", "P01", "P02", "P03"));
        h.run(1);
        assertThatThrownBy(() -> h.traffic().requestRoute("AGV-1", "P04", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TrafficErrorCode.VEHICLE_BUSY));

        h.run(10);
        RouteViewDto route = h.traffic().requestRoute("AGV-1", "P04", null);

        assertThat(route.nodes()).startsWith("P01");
        assertThat(h.runUntil(60, () -> h.arrived("AGV-1"))).isTrue();
    }

    @Test
    void aVehicleThatLostItsOrderEndsItsRoute() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 5, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);
        h.traffic().requestRoute("AGV-1", "P04", null);
        h.run(2);

        // as after a reboot: same place, no order
        h.restartVehicle("AGV-1");
        h.run(2);

        RouteViewDto route = h.traffic().route("AGV-1").orElseThrow();
        assertThat(route.status()).isEqualTo(RouteStatus.FAILED);
        assertThat(h.progress.getLast().reason()).contains("no longer runs order");
        assertThat(h.traffic().requestRoute("AGV-1", "P00", null).status()).isNotEqualTo(RouteStatus.FAILED);
    }

    @Test
    void aNewRouteWaitsUntilTheCancelledOneHasStopped() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 5, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);
        h.traffic().requestRoute("AGV-1", "P04", null);
        h.run(3);
        h.traffic().cancelRoute("AGV-1");
        h.run(0.1);

        assertThatThrownBy(() -> h.traffic().requestRoute("AGV-1", "P00", null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(TrafficErrorCode.VEHICLE_BUSY));
        h.run(10);
        // it stopped on the next point and takes the new route
        assertThat(h.traffic().requestRoute("AGV-1", "P00", null).status()).isNotEqualTo(RouteStatus.FAILED);
        assertThat(h.runUntil(60, () -> h.arrived("AGV-1"))).isTrue();
    }

    @Test
    void newMapVersionIsUsedOnceNoRouteRunsOnIt() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 3, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);
        h.traffic().requestRoute("AGV-1", "P02", null);
        h.run(2);

        MapTopologyDto slower = TestMaps.grid(1, 3, 4);
        slower = new MapTopologyDto(slower.mapCode(), 2, slower.points(), slower.paths().stream()
                .map(p -> new PathDto(p.code(), p.fromPoint(), p.toPoint(), PathDirection.BIDIRECTIONAL, PathShape.LINE,
                        List.of(), null, VehicleOrientation.ANY, null, 3.0, true, p.length()))
                .toList(), List.of());
        h.publishMap(slower);
        CostQueryDto query = new CostQueryDto(TestMaps.MAP, List.of("P00"), List.of("P02"));
        assertThat(h.traffic().costs(query).seconds().getFirst().getFirst()).isEqualTo(8.0);

        h.runUntil(60, () -> h.arrived("AGV-1"));
        h.traffic().tick();
        assertThat(h.traffic().costs(query).seconds().getFirst().getFirst()).isEqualTo(24.0);
    }

    @Test
    void costMatrixReportsUnreachableAsNull() {
        MapTopologyDto map = new MapTopologyDto(TestMaps.MAP, 1,
                List.of(TestMaps.point("A", 0, 0), TestMaps.point("B", 4, 0), TestMaps.point("Z", 50, 50)),
                List.of(TestMaps.path("AB", "A", "B", PathDirection.BIDIRECTIONAL, 4)), List.of());
        TrafficHarness h = new TrafficHarness(map);

        CostMatrixDto costs = h.traffic().costs(new CostQueryDto(TestMaps.MAP, List.of("A", "B"), List.of("B", "Z")));

        assertThat(costs.seconds()).containsExactly(java.util.Arrays.asList(4.0, null), java.util.Arrays.asList(0.0, null));
    }

    @Test
    void invalidRequestsAreRejected() {
        TrafficHarness h = new TrafficHarness(TestMaps.grid(1, 3, 4)).vehicle("AGV-1", "P00");
        h.run(0.2);

        assertError(() -> h.traffic().requestRoute("NOPE", "P02", null), TrafficErrorCode.VEHICLE_UNKNOWN);
        assertError(() -> h.traffic().requestRoute("AGV-1", "X", null), TrafficErrorCode.UNKNOWN_POINT);
        h.traffic().requestRoute("AGV-1", "P02", null);
        assertError(() -> h.traffic().requestRoute("AGV-1", "P01", null), TrafficErrorCode.ROUTE_ACTIVE);
        h.run(2);
        h.traffic().cancelRoute("AGV-1");
        h.run(5);
        assertError(() -> h.traffic().cancelRoute("AGV-1"), TrafficErrorCode.NO_ACTIVE_ROUTE);
        // pushed off the points by hand
        h.fleet().relocate("AGV-1", 2, 0.5, 0, TestMaps.MAP, null);
        h.run(1.1);
        assertError(() -> h.traffic().requestRoute("AGV-1", "P00", null), TrafficErrorCode.VEHICLE_NOT_ON_NODE);
    }

    private static void assertError(ThrowingCallable call, TrafficErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
