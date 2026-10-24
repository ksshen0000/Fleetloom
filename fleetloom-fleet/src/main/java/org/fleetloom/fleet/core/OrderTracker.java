package org.fleetloom.fleet.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.fleetloom.common.error.BizException;
import org.fleetloom.contracts.fleet.RouteCommandDto;
import org.fleetloom.contracts.fleet.RouteEdgeDto;
import org.fleetloom.contracts.fleet.RouteNodeDto;
import org.fleetloom.contracts.fleet.VehicleActionDto;
import org.fleetloom.contracts.map.PositionDto;
import org.fleetloom.vda5050.v2.Action;
import org.fleetloom.vda5050.v2.ActionParameter;
import org.fleetloom.vda5050.v2.BlockingType;
import org.fleetloom.vda5050.v2.ControlPoint;
import org.fleetloom.vda5050.v2.Edge;
import org.fleetloom.vda5050.v2.Header;
import org.fleetloom.vda5050.v2.Node;
import org.fleetloom.vda5050.v2.NodePosition;
import org.fleetloom.vda5050.v2.NodeState;
import org.fleetloom.vda5050.v2.Order;
import org.fleetloom.vda5050.v2.OrderRules;
import org.fleetloom.vda5050.v2.State;
import org.fleetloom.vda5050.v2.Trajectory;

/**
 * Translates protocol-independent route commands into VDA 5050 orders for one vehicle and remembers
 * what was sent: order id, update id and the decision point, so that updates continue correctly.
 */
final class OrderTracker {

    private String orderId;
    private long orderUpdateId;
    private String decisionNodeId;
    private long decisionSequenceId;

    String orderId() {
        return orderId;
    }

    long orderUpdateId() {
        return orderUpdateId;
    }

    /** Builds a new order; sequence ids start at 0. */
    Order newOrder(String newOrderId, RouteCommandDto command) {
        Order order = build(newOrderId, 0, 0, command);
        orderId = newOrderId;
        orderUpdateId = 0;
        rememberDecisionPoint(order);
        return order;
    }

    /**
     * Takes over the order a vehicle reports when none is known here, e.g. after this service
     * restarted, so that updates continue it: the decision point is the last released node the
     * vehicle still has to reach, or the node it last passed.
     */
    void adoptIfUnknown(State state) {
        if (orderId != null || state.orderId() == null || state.orderId().isEmpty()) {
            return;
        }
        orderId = state.orderId();
        orderUpdateId = state.orderUpdateId();
        state.nodeStates().stream().filter(NodeState::released)
                .max(Comparator.comparingLong(NodeState::sequenceId))
                .ifPresentOrElse(n -> {
                    decisionNodeId = n.nodeId();
                    decisionSequenceId = n.sequenceId();
                }, () -> {
                    decisionNodeId = state.lastNodeId();
                    decisionSequenceId = state.lastNodeSequenceId();
                });
    }

    /** Builds an update of the current order; its first node must be the current decision point. */
    Order update(RouteCommandDto command) {
        if (orderId == null) {
            throw new BizException(FleetErrorCode.NO_ACTIVE_ORDER, "no order has been sent to this vehicle");
        }
        requireNodes(command);
        String first = command.nodes().getFirst().pointCode();
        if (!first.equals(decisionNodeId)) {
            throw new BizException(FleetErrorCode.ROUTE_NOT_CONTINUOUS,
                    "update must start at the decision point %s, not %s".formatted(decisionNodeId, first));
        }
        Order order = build(orderId, orderUpdateId + 1, decisionSequenceId, command);
        orderUpdateId++;
        rememberDecisionPoint(order);
        return order;
    }

    private Order build(String id, long updateId, long firstSequenceId, RouteCommandDto command) {
        requireNodes(command);
        List<RouteNodeDto> routeNodes = command.nodes();
        List<RouteEdgeDto> routeEdges = command.edges() == null ? List.of() : command.edges();
        if (routeEdges.size() != routeNodes.size() - 1) {
            throw new BizException(FleetErrorCode.INVALID_ROUTE, "%d nodes need %d edges, got %d"
                    .formatted(routeNodes.size(), routeNodes.size() - 1, routeEdges.size()));
        }
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        long seq = firstSequenceId;
        for (int i = 0; i < routeNodes.size(); i++) {
            RouteNodeDto rn = routeNodes.get(i);
            if (i > 0) {
                RouteNodeDto start = routeNodes.get(i - 1);
                RouteEdgeDto re = routeEdges.get(i - 1);
                String edgeId = re.pathCode() == null ? start.pointCode() + "-" + rn.pointCode() : re.pathCode();
                edges.add(new Edge(edgeId, seq, null, re.released(), start.pointCode(), rn.pointCode(), re.maxSpeed(),
                        null, null, null, null, null, null, null, null, trajectory(start, re, rn), null,
                        actions(id, seq, re.actions())));
                seq++;
            }
            nodes.add(new Node(rn.pointCode(), seq, null, rn.released(),
                    new NodePosition(rn.x(), rn.y(), rn.theta(), null, null, command.mapCode(), null),
                    actions(id, seq, rn.actions())));
            seq++;
        }
        Order order = new Order(Header.UNSTAMPED, id, updateId, nodes, edges);
        List<String> problems = OrderRules.validate(order);
        if (!problems.isEmpty()) {
            throw new BizException(FleetErrorCode.INVALID_ROUTE, String.join("; ", problems), problems);
        }
        return order;
    }

    private void rememberDecisionPoint(Order order) {
        Node last = OrderRules.baseNodes(order).getLast();
        decisionNodeId = last.nodeId();
        decisionSequenceId = last.sequenceId();
    }

    private static void requireNodes(RouteCommandDto command) {
        if (command.nodes() == null || command.nodes().isEmpty()) {
            throw new BizException(FleetErrorCode.INVALID_ROUTE, "a route needs at least one node");
        }
    }

    private static Trajectory trajectory(RouteNodeDto start, RouteEdgeDto edge, RouteNodeDto end) {
        List<PositionDto> c = edge.controlPoints();
        if (c == null || c.isEmpty()) {
            return null;
        }
        if (c.size() != 2) {
            throw new BizException(FleetErrorCode.INVALID_ROUTE,
                    "edge %s: a curve needs 2 control points, got %d".formatted(edge.pathCode(), c.size()));
        }
        return Trajectory.cubicBezier(new ControlPoint(start.x(), start.y(), null),
                new ControlPoint(c.get(0).x(), c.get(0).y(), null), new ControlPoint(c.get(1).x(), c.get(1).y(), null),
                new ControlPoint(end.x(), end.y(), null));
    }

    private static List<Action> actions(String orderId, long sequenceId, List<VehicleActionDto> dtos) {
        if (dtos == null) {
            return List.of();
        }
        List<Action> result = new ArrayList<>();
        for (int i = 0; i < dtos.size(); i++) {
            VehicleActionDto a = dtos.get(i);
            String id = a.actionId() != null ? a.actionId() : "%s-%d-%d".formatted(orderId, sequenceId, i);
            result.add(new Action(a.actionType(), id, null, toBlocking(a), parameters(a.parameters())));
        }
        return result;
    }

    static BlockingType toBlocking(VehicleActionDto a) {
        return a.blocking() == null ? BlockingType.HARD : BlockingType.valueOf(a.blocking().name());
    }

    static List<ActionParameter> parameters(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return null;
        }
        return parameters.entrySet().stream().map(e -> new ActionParameter(e.getKey(), e.getValue())).toList();
    }
}
