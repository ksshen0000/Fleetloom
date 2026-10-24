package org.fleetloom.fleet.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.contracts.fleet.BlockingMode;
import org.fleetloom.contracts.fleet.RouteCommandDto;
import org.fleetloom.contracts.fleet.RouteEdgeDto;
import org.fleetloom.contracts.fleet.RouteNodeDto;
import org.fleetloom.contracts.fleet.VehicleActionDto;
import org.fleetloom.contracts.map.PositionDto;
import org.fleetloom.vda5050.v2.Action;
import org.fleetloom.vda5050.v2.BlockingType;
import org.fleetloom.vda5050.v2.Edge;
import org.fleetloom.vda5050.v2.Node;
import org.fleetloom.vda5050.v2.Order;

class OrderTrackerTest {

    private final OrderTracker tracker = new OrderTracker();

    @Test
    void newOrderAssignsAlternatingSequenceIdsAndMapPositions() {
        Order order = tracker.newOrder("o1", Routes.on("WH1").node("A", 0, 0).node("B", 10, 0).node("C", 10, 10).build());

        assertThat(order.nodes()).extracting(Node::nodeId, Node::sequenceId)
                .containsExactly(tuple("A", 0L), tuple("B", 2L), tuple("C", 4L));
        assertThat(order.edges()).extracting(Edge::edgeId, Edge::sequenceId, Edge::startNodeId, Edge::endNodeId)
                .containsExactly(tuple("AB", 1L, "A", "B"), tuple("BC", 3L, "B", "C"));
        assertThat(order.nodes().get(1).nodePosition().mapId()).isEqualTo("WH1");
        assertThat(order.orderUpdateId()).isZero();
    }

    @Test
    void horizonIsSentUnreleased() {
        Order order = tracker.newOrder("o1", Routes.on("WH1").node("A", 0, 0).node("B", 10, 0).horizon().node("C", 20, 0).build());

        assertThat(order.nodes()).extracting(Node::released).containsExactly(true, true, false);
        assertThat(order.edges()).extracting(Edge::released).containsExactly(true, false);
    }

    @Test
    void updateContinuesFromTheDecisionPoint() {
        tracker.newOrder("o1", Routes.on("WH1").node("A", 0, 0).node("B", 10, 0).horizon().node("C", 20, 0).build());

        Order update = tracker.update(Routes.on("WH1").node("B", 10, 0).node("C", 20, 0).build());

        assertThat(update.orderId()).isEqualTo("o1");
        assertThat(update.orderUpdateId()).isEqualTo(1);
        assertThat(update.nodes()).extracting(Node::nodeId, Node::sequenceId)
                .containsExactly(tuple("B", 2L), tuple("C", 4L));
        assertThat(update.edges()).extracting(Edge::sequenceId).containsExactly(3L);

        Order second = tracker.update(Routes.on("WH1").node("C", 20, 0).node("D", 30, 0).build());
        assertThat(second.orderUpdateId()).isEqualTo(2);
        assertThat(second.nodes()).extracting(Node::sequenceId).containsExactly(4L, 6L);
    }

    @Test
    void updateMustStartAtTheDecisionPoint() {
        tracker.newOrder("o1", Routes.on("WH1").node("A", 0, 0).node("B", 10, 0).build());

        assertError(() -> tracker.update(Routes.on("WH1").node("A", 0, 0).node("C", 0, 10).build()),
                FleetErrorCode.ROUTE_NOT_CONTINUOUS);
        assertThat(tracker.orderUpdateId()).isZero();
    }

    @Test
    void updateWithoutOrderIsRejected() {
        assertError(() -> tracker.update(Routes.on("WH1").node("A", 0, 0).build()), FleetErrorCode.NO_ACTIVE_ORDER);
    }

    @Test
    void actionsGetIdsAndBlockingTypes() {
        VehicleActionDto pick = new VehicleActionDto("pick", null, null, Map.of("loadId", "P1"));
        VehicleActionDto beep = new VehicleActionDto("beep", "my-beep", BlockingMode.NONE, null);

        Order order = tracker.newOrder("o1", Routes.on("WH1").node("A", 0, 0).node("B", 10, 0, pick, beep).build());

        List<Action> actions = order.nodes().get(1).actions();
        assertThat(actions).extracting(Action::actionId).containsExactly("o1-2-0", "my-beep");
        assertThat(actions).extracting(Action::blockingType).containsExactly(BlockingType.HARD, BlockingType.NONE);
        assertThat(actions.getFirst().parameter("loadId")).contains("P1");
    }

    @Test
    void curvedEdgeBecomesABezierTrajectory() {
        RouteCommandDto route = new RouteCommandDto("WH1",
                List.of(new RouteNodeDto("A", 0, 0, null, true, List.of()), new RouteNodeDto("B", 10, 10, null, true, List.of())),
                List.of(new RouteEdgeDto("AB", true, 0.5, List.of(new PositionDto(5, 0), new PositionDto(10, 5)), List.of())));

        Edge edge = tracker.newOrder("o1", route).edges().getFirst();

        assertThat(edge.maxSpeed()).isEqualTo(0.5);
        assertThat(edge.trajectory().degree()).isEqualTo(3);
        assertThat(edge.trajectory().controlPoints()).extracting(c -> c.x()).containsExactly(0.0, 5.0, 10.0, 10.0);
    }

    @Test
    void malformedRoutesAreRejected() {
        assertError(() -> tracker.newOrder("o1", new RouteCommandDto("WH1", List.of(), List.of())), FleetErrorCode.INVALID_ROUTE);
        assertError(() -> tracker.newOrder("o1", new RouteCommandDto("WH1",
                        List.of(new RouteNodeDto("A", 0, 0, null, true, List.of()), new RouteNodeDto("B", 1, 0, null, true, List.of())),
                        List.of())),
                FleetErrorCode.INVALID_ROUTE);
        assertError(() -> tracker.newOrder("o1", Routes.on("WH1").horizon().node("A", 0, 0).build()),
                FleetErrorCode.INVALID_ROUTE);
    }

    private static org.assertj.core.groups.Tuple tuple(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }

    private static void assertError(ThrowingCallable call, FleetErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
