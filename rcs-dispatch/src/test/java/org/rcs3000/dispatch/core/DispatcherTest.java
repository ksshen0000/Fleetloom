package org.rcs3000.dispatch.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.rcs3000.common.error.BizException;
import org.rcs3000.contracts.dispatch.CreateOrderDto;
import org.rcs3000.contracts.dispatch.OrderOverdueEvent;
import org.rcs3000.contracts.dispatch.OrderStatus;
import org.rcs3000.contracts.dispatch.OrderType;
import org.rcs3000.contracts.dispatch.OrderViewDto;

/**
 * Dispatching end to end: orders become vehicle work through the real traffic controller, fleet
 * service and simulated vehicles. Grid points are 4 m apart, P00 at (0, 0), P22 at (8, 8).
 */
class DispatcherTest {

    private static CreateOrderDto transport(String carrier, String target) {
        return new CreateOrderDto(null, OrderType.TRANSPORT, null, carrier, target, null, null, null, null);
    }

    /** Locations L<rc> on every grid point, zone Z; carrier C1 in L00. */
    private static DispatchHarness grid() {
        DispatchHarness h = new DispatchHarness();
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                h.resource.location("L" + r + c, "Z", "P" + r + c);
            }
        }
        h.resource.carrier("C1", "SHELF", "L00");
        return h;
    }

    @Test
    void transportIsCarriedOutFromPickUpToDropOff() {
        DispatchHarness h = grid().vehicle("AGV-1", "P10");
        h.run(0.2);

        OrderViewDto order = h.dispatcher().create(transport("C1", "L22"));
        assertThat(order.sourceLocation()).isEqualTo("L00");
        assertThat(h.resource.status("L00")).isEqualTo("LOCKED_OUT");
        assertThat(h.resource.status("L22")).isEqualTo("LOCKED_IN");
        assertThat(order.legs()).extracting(OrderViewDto.Leg::point).containsExactly("P00", "P22");

        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 120)).isTrue();
        OrderViewDto done = h.dispatcher().get(order.orderNo());
        assertThat(done.assignedVehicle()).isEqualTo("AGV-1");
        assertThat(done.legs()).extracting(OrderViewDto.Leg::status).containsExactly("DONE", "DONE");
        assertThat(h.resource.locationOf("C1")).isEqualTo("STORAGE:L22");
        assertThat(h.resource.status("L00")).isEqualTo("EMPTY");
        assertThat(h.resource.status("L22")).isEqualTo("OCCUPIED");
        assertThat(h.simulated("AGV-1").lastNodeId()).isEqualTo("P22");
        assertThat(h.simulated("AGV-1").loads()).isEmpty();
        assertThat(h.orders.findByNo(order.orderNo()).orElseThrow().status()).isEqualTo(OrderStatus.COMPLETED);
    }

    @Test
    void nearestVehicleGetsTheOrder() {
        DispatchHarness h = grid().vehicle("FAR", "P22").vehicle("NEAR", "P01");
        h.run(0.2);

        OrderViewDto order = h.dispatcher().create(transport("C1", "L20"));
        h.run(1.1);

        assertThat(h.dispatcher().get(order.orderNo()).assignedVehicle()).isEqualTo("NEAR");
    }

    @Test
    void higherPriorityGoesFirst() {
        DispatchHarness h = grid().vehicle("AGV-1", "P11");
        h.resource.carrier("C2", "SHELF", "L02");
        h.run(0.2);
        // orders queue up while the vehicle does not accept work
        h.dispatcher().setAccepting("AGV-1", false);
        OrderViewDto low = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, 2, "C1", "L20", null, null, null, null));
        OrderViewDto high = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, 8, "C2", "L22", null, null, null, null));
        h.run(2);
        assertThat(h.dispatcher().get(low.orderNo()).status()).isEqualTo(OrderStatus.QUEUED);

        h.dispatcher().setAccepting("AGV-1", true);
        h.run(1.1);

        assertThat(h.dispatcher().get(high.orderNo()).status()).isEqualTo(OrderStatus.EXECUTING);
        assertThat(h.dispatcher().get(low.orderNo()).status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(h.orderReaches(low.orderNo(), OrderStatus.COMPLETED, 240)).isTrue();
    }

    @Test
    void targetZonePicksTheNearestEmptyLocation() {
        DispatchHarness h = grid().vehicle("AGV-1", "P11");
        h.resource.location("FAR", "OUT", "P22");
        h.resource.location("NEAR", "OUT", "P01");
        h.run(0.2);

        OrderViewDto order = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, null, "C1", null, "OUT",
                null, null, null));

        assertThat(order.targetLocation()).isEqualTo("NEAR");
    }

    @Test
    void unavailableCarrierOrTargetRejectsTheOrder() {
        DispatchHarness h = grid().vehicle("AGV-1", "P11");
        h.resource.carrier("C2", "SHELF", "L22");

        assertThat(h.dispatcher().create(transport("NOPE", "L01")).status()).isEqualTo(OrderStatus.REJECTED);
        OrderViewDto occupied = h.dispatcher().create(transport("C1", "L22"));
        assertThat(occupied.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(occupied.reason()).contains("busy");
        assertThat(h.resource.status("L00")).isEqualTo("OCCUPIED");
    }

    @Test
    void sameExternalNumberReturnsTheSameOrder() {
        DispatchHarness h = grid();
        CreateOrderDto request = new CreateOrderDto("WMS-42", OrderType.TRANSPORT, null, "C1", "L22", null, null, null, null);

        OrderViewDto first = h.dispatcher().create(request);
        OrderViewDto again = h.dispatcher().create(request);

        assertThat(again.orderNo()).isEqualTo(first.orderNo());
    }

    @Test
    void cancellingBeforePickUpReleasesTheLocations() {
        DispatchHarness h = grid().vehicle("AGV-1", "P22");
        h.run(0.2);
        OrderViewDto order = h.dispatcher().create(transport("C1", "L20"));
        h.run(3);

        OrderViewDto cancelled = h.dispatcher().cancel(order.orderNo());
        h.run(5);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(h.resource.status("L00")).isEqualTo("OCCUPIED");
        assertThat(h.resource.status("L20")).isEqualTo("EMPTY");
        assertThat(h.simulated("AGV-1").isDriving()).isFalse();
    }

    @Test
    void cancellingWithTheCarrierOnBoardIsRefused() {
        DispatchHarness h = grid().vehicle("AGV-1", "P01");
        h.run(0.2);
        OrderViewDto order = h.dispatcher().create(transport("C1", "L22"));
        assertThat(h.runUntil(60, () -> h.resource.locationOf("C1").startsWith("VEHICLE"))).isTrue();

        assertThatThrownBy(() -> h.dispatcher().cancel(order.orderNo()))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(DispatchErrorCode.CARRIER_ON_VEHICLE));
        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 120)).isTrue();
    }

    @Test
    void unregisteredOrIncompatibleVehiclesAreNotUsed() {
        DispatchHarness h = grid().vehicle("AGV-1", "P11");
        h.resource.compatibility.put("SHELF", Set.of("FORKLIFT"));
        h.run(0.2);

        OrderViewDto order = h.dispatcher().create(transport("C1", "L22"));
        h.run(3);

        assertThat(h.dispatcher().get(order.orderNo()).status()).isEqualTo(OrderStatus.QUEUED);
        assertThat(h.dispatcher().vehicles()).singleElement()
                .satisfies(v -> assertThat(v.unavailable()).isNull());
    }

    @Test
    void lowBatteryVehicleGoesChargingAndWorksAgainWhenFull() {
        DispatchHarness h = grid().vehicle("AGV-1", "P11");
        h.simulated("AGV-1").setBattery(15);
        h.run(2.2);

        List<OrderViewDto> charging = h.dispatcher().list(null, 10);
        assertThat(charging).singleElement().satisfies(o -> {
            assertThat(o.type()).isEqualTo(OrderType.CHARGE);
            assertThat(o.legs().getFirst().point()).isEqualTo("CH1");
        });
        assertThat(h.orderReaches(charging.getFirst().orderNo(), OrderStatus.COMPLETED, 60)).isTrue();
        assertThat(h.simulated("AGV-1").isCharging()).isTrue();

        // while charging below 90 % the vehicle does not take work
        OrderViewDto order = h.dispatcher().create(transport("C1", "L22"));
        h.run(3);
        assertThat(h.dispatcher().get(order.orderNo()).status()).isEqualTo(OrderStatus.QUEUED);

        assertThat(h.orderReaches(order.orderNo(), OrderStatus.EXECUTING, 120)).isTrue();
        assertThat(h.simulated("AGV-1").battery()).isGreaterThanOrEqualTo(89.0);
        h.run(2);
        assertThat(h.simulated("AGV-1").isCharging()).isFalse();
        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 120)).isTrue();
    }

    @Test
    void idleVehicleParkedOnTheDropPointGivesWay() {
        DispatchHarness h = grid().vehicle("AGV-1", "P01").vehicle("PARKED", "P21");
        h.run(0.2);

        OrderViewDto order = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, null, "C1", "L21", null,
                null, null, "AGV-1"));

        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 180)).isTrue();
        assertThat(h.simulated("PARKED").lastNodeId()).isNotEqualTo("P21");
        assertThat(h.dispatcher().list(null, 10)).anySatisfy(o -> {
            assertThat(o.orderNo()).startsWith("GW-");
            assertThat(o.assignedVehicle()).isEqualTo("PARKED");
            assertThat(o.reason()).isEqualTo("give way to AGV-1");
        });
        assertThat(h.minDistance()).isGreaterThan(2.0);
    }

    @Test
    void idleVehicleIsParkedAfterAWhile() {
        DispatchHarness h = new DispatchHarness(true).vehicle("AGV-1", "P01");

        h.run(20);
        assertThat(h.simulated("AGV-1").lastNodeId()).isEqualTo("P01");

        assertThat(h.runUntil(60, () -> "PK1".equals(h.simulated("AGV-1").lastNodeId()))).isTrue();
        assertThat(h.dispatcher().list(null, 10)).anySatisfy(o -> {
            assertThat(o.orderNo()).startsWith("PK-");
            assertThat(o.assignedVehicle()).isEqualTo("AGV-1");
            assertThat(o.priority()).isEqualTo(1);
        });

        // once parked it stays put, and still takes work
        h.run(60);
        assertThat(h.dispatcher().list(null, 10)).filteredOn(o -> o.orderNo().startsWith("PK-")).hasSize(1);
        h.resource.location("L22", "Z", "P22");
        h.resource.location("L00", "Z", "P00");
        h.resource.carrier("C1", "SHELF", "L00");
        OrderViewDto order = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, null, "C1", "L22", null,
                null, null, null));
        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 180)).isTrue();
    }

    @Test
    void vehicleOnItsWayToParkGivesUpWhenItDeadlocksWithOneLeavingTheParkingPoint() {
        DispatchHarness h = new DispatchHarness(true).vehicle("AGV-1", "P20");
        assertThat(h.runUntil(60, () -> h.dispatcher().list(null, 10).stream()
                .anyMatch(o -> o.orderNo().startsWith("PK-")))).isTrue();

        // just then, before AGV-1 got near, a vehicle is put on the parking point by hand. AGV-1 waits at
        // P02 for it, it is asked to give way, and can only do so through P02: they wait for each other
        h.vehicle("AGV-2", "PK1");

        assertThat(h.runUntil(120, () -> h.dispatcher().list(null, 10).stream().anyMatch(o -> o.orderNo().startsWith("GW-")
                && "AGV-2".equals(o.assignedVehicle()) && o.status() == OrderStatus.COMPLETED))).isTrue();
        assertThat(h.dispatcher().list(null, 10)).anySatisfy(o -> {
            assertThat(o.orderNo()).startsWith("PK-");
            assertThat(o.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(o.reason()).isEqualTo("deadlock on the way to park");
        });
        assertThat(h.simulated("AGV-2").lastNodeId()).isNotEqualTo("PK1");
        assertThat(h.minDistance()).isGreaterThan(1.0);
    }

    @Test
    void ordersRideOutAShortOutageOfTrafficControlOrStorage() {
        DispatchHarness h = grid().vehicle("AGV-1", "P01");
        h.run(0.2);

        h.trafficDown = true;
        OrderViewDto order = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, null, "C1", "L22", null,
                null, null, null));
        h.run(10);
        assertThat(h.dispatcher().get(order.orderNo()).status()).isEqualTo(OrderStatus.EXECUTING);
        h.trafficDown = false;

        // storage goes away just as the carrier is lifted
        assertThat(h.runUntil(60, () -> h.simulated("AGV-1").lastNodeId().equals("P00"))).isTrue();
        h.resource.down = true;
        h.run(20);
        assertThat(h.dispatcher().get(order.orderNo()).status()).isEqualTo(OrderStatus.EXECUTING);
        h.resource.down = false;

        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 180)).isTrue();
        assertThat(h.resource.locationOf("C1")).isEqualTo("STORAGE:L22");
    }

    @Test
    void anOutageThatDoesNotPassSuspendsTheOrder() {
        DispatchHarness h = grid().vehicle("AGV-1", "P01");
        h.run(0.2);

        h.trafficDown = true;
        OrderViewDto order = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, null, "C1", "L22", null,
                null, null, null));

        assertThat(h.orderReaches(order.orderNo(), OrderStatus.SUSPENDED,
                Dispatcher.RETRY_PASSING_FOR.toSeconds() + 10)).isTrue();
        assertThat(h.dispatcher().get(order.orderNo()).reason()).contains("rcs-traffic is unreachable");
    }

    @Test
    void onlyOneVehicleParksOnAParkingPointAndOthersStay() {
        DispatchHarness h = new DispatchHarness(true).vehicle("AGV-1", "P02").vehicle("AGV-2", "P20");

        assertThat(h.runUntil(90, () -> "PK1".equals(h.simulated("AGV-1").lastNodeId()))).isTrue();
        h.run(60);
        assertThat(h.simulated("AGV-2").lastNodeId()).isEqualTo("P20");
        assertThat(h.minDistance()).isGreaterThan(2.0);
    }

    @Test
    void vehiclesNotAcceptingOrdersAreNotParked() {
        DispatchHarness h = new DispatchHarness(true).vehicle("AGV-1", "P01");
        h.run(0.2);
        h.dispatcher().setAccepting("AGV-1", false);

        h.run(90);
        assertThat(h.simulated("AGV-1").lastNodeId()).isEqualTo("P01");
    }

    @Test
    void orderMissingItsDeadlineIsReportedOnce() {
        DispatchHarness h = grid().vehicle("AGV-1", "P22");
        List<OrderOverdueEvent> overdue = new java.util.concurrent.CopyOnWriteArrayList<>();
        h.events().subscribe(OrderOverdueEvent.TYPE, "test", OrderOverdueEvent.class, e -> overdue.add(e.payload()));
        h.run(0.2);

        OrderViewDto order = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, null, "C1", "L02", null,
                null, null, null, h.clock().instant().plusSeconds(10)));
        assertThat(order.deadline()).isNotNull();
        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 180)).isTrue();

        assertThat(overdue).singleElement().satisfies(o -> assertThat(o.order().orderNo()).isEqualTo(order.orderNo()));
        assertThat(h.dispatcher().list(null, 5).getFirst().deadline()).isEqualTo(order.deadline());
    }

    @Test
    void templateDecidesActionsAndViaPoints() {
        DispatchHarness h = grid().vehicle("AGV-1", "P01");
        h.templates.save(new OrderTemplate("WEIGH", "过磅搬运", "pick", "drop", List.of("P11"), "detectObject", null));
        h.run(0.2);

        OrderViewDto order = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, null, "C1", "L22", null,
                null, null, null, null, "WEIGH"));

        assertThat(order.legs()).extracting(OrderViewDto.Leg::point).containsExactly("P00", "P11", "P22");
        assertThat(order.legs()).extracting(OrderViewDto.Leg::action).containsExactly("pick", "detectObject", "drop");
        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 240)).isTrue();
        assertThat(h.resource.locationOf("C1")).isEqualTo("STORAGE:L22");
    }

    @Test
    void unknownTemplateRejectsTheOrderWithoutLockingAnything() {
        DispatchHarness h = grid().vehicle("AGV-1", "P01");
        h.run(0.2);

        OrderViewDto order = h.dispatcher().create(new CreateOrderDto(null, OrderType.TRANSPORT, null, "C1", "L22", null,
                null, null, null, null, "NOPE"));

        assertThat(order.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.reason()).contains("NOPE");
        assertThat(h.resource.status("L22")).isEqualTo("EMPTY");
    }

    @Test
    void vehicleGoingOfflineSuspendsItsOrder() {
        DispatchHarness h = grid().vehicle("AGV-1", "P22");
        h.run(0.2);
        OrderViewDto order = h.dispatcher().create(transport("C1", "L20"));
        h.run(3);

        h.removeVehicle("AGV-1");
        h.run(1);

        OrderViewDto suspended = h.dispatcher().get(order.orderNo());
        assertThat(suspended.status()).isEqualTo(OrderStatus.SUSPENDED);
        assertThat(suspended.reason()).contains("OFFLINE");
    }

    @Test
    void removedVehiclesAreForgottenUnlessAnOrderStillNeedsThem() {
        DispatchHarness h = grid().vehicle("AGV-1", "P22").vehicle("AGV-2", "P00");
        h.run(0.2);
        OrderViewDto order = h.dispatcher().create(transport("C1", "L20"));
        h.run(3);
        String busy = h.dispatcher().get(order.orderNo()).assignedVehicle();
        String idle = busy.equals("AGV-1") ? "AGV-2" : "AGV-1";

        h.removeVehicle(busy);
        h.removeVehicle(idle);
        h.run(1);
        h.dispatcher().onVehicleForgotten(busy);
        h.dispatcher().onVehicleForgotten(idle);

        assertThat(h.dispatcher().vehicles()).extracting(Dispatcher.VehicleView::code).containsExactly(busy);
    }

    @Test
    void ordersCarryOnAfterTrafficControlRestarts() {
        DispatchHarness h = grid().vehicle("AGV-1", "P22");
        h.run(0.2);
        OrderViewDto order = h.dispatcher().create(transport("C1", "L20"));
        h.run(3);
        assertThat(h.dispatcher().get(order.orderNo()).status()).isEqualTo(OrderStatus.EXECUTING);

        h.restartTraffic();

        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 120)).isTrue();
        assertThat(h.resource.locationOf("C1")).isEqualTo("STORAGE:L20");
    }

    @Test
    void manualSuspendAndResume() {
        DispatchHarness h = grid().vehicle("AGV-1", "P22");
        h.run(0.2);
        OrderViewDto order = h.dispatcher().create(transport("C1", "L20"));
        h.run(5);

        h.dispatcher().suspend(order.orderNo());
        h.run(5);
        assertThat(h.simulated("AGV-1").isDriving()).isFalse();
        // the vehicle stopped between two points; let it be put back on the nearest one by hand
        var pose = h.simulated("AGV-1").pose();
        String nearest = "P" + Math.round(pose.y() / 4) + Math.round(pose.x() / 4);
        h.dispatcherRelocate("AGV-1", nearest);

        h.dispatcher().resume(order.orderNo());
        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 120)).isTrue();
    }

    @Test
    void resumingRightAfterSuspendingWaitsForTheVehicleToStop() {
        DispatchHarness h = grid().vehicle("AGV-1", "P20");
        h.run(0.2);
        OrderViewDto order = h.dispatcher().create(transport("C1", "L22"));
        h.run(3);

        // the cancelled route is still winding down when the order asks for a new one
        h.dispatcher().suspend(order.orderNo());
        h.run(0.1);
        h.dispatcher().resume(order.orderNo());

        assertThat(h.dispatcher().get(order.orderNo()).status()).isEqualTo(OrderStatus.EXECUTING);
        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 120)).isTrue();
    }

    @Test
    void restartedDispatcherFinishesRunningOrders() {
        DispatchHarness h = grid().vehicle("AGV-1", "P01");
        h.run(0.2);
        OrderViewDto order = h.dispatcher().create(transport("C1", "L22"));
        h.run(8);

        h.restartDispatcher();

        assertThat(h.orderReaches(order.orderNo(), OrderStatus.COMPLETED, 120)).isTrue();
        assertThat(h.resource.locationOf("C1")).isEqualTo("STORAGE:L22");
    }

    @Test
    void manyOrdersWithSeveralVehiclesAllCompleteSafely() {
        DispatchHarness h = grid().vehicle("V1", "P02").vehicle("V2", "P20").vehicle("V3", "P11");
        h.resource.carrier("C2", "SHELF", "L01");
        h.resource.carrier("C3", "SHELF", "L10");
        h.run(0.2);

        List<String> orders = new ArrayList<>();
        orders.add(h.dispatcher().create(transport("C1", "L22")).orderNo());
        orders.add(h.dispatcher().create(transport("C2", "L21")).orderNo());
        orders.add(h.dispatcher().create(transport("C3", "L12")).orderNo());

        assertThat(h.runUntil(300, () -> orders.stream()
                .allMatch(o -> h.dispatcher().get(o).status() == OrderStatus.COMPLETED))).isTrue();
        assertThat(orders.stream().map(o -> h.dispatcher().get(o).assignedVehicle()).distinct()).hasSize(3);
        assertThat(h.minDistance()).isGreaterThan(2.0);
        assertThat(h.resource.locationOf("C2")).isEqualTo("STORAGE:L21");
    }
}
