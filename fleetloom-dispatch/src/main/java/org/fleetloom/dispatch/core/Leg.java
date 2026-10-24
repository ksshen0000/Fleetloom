package org.fleetloom.dispatch.core;

import java.util.Map;

/**
 * One drive of the vehicle within an order, ending with an optional action at the point.
 *
 * @param kind   what completing the leg means for the order
 * @param action VDA 5050 action type at the point, or {@code null}
 */
public record Leg(Kind kind, String point, String action, Map<String, Object> parameters, Status status) {

    public enum Kind {
        /** Lift the carrier out of its source location. */
        PICKUP,
        /** Set the carrier down in its target location. */
        DROP,
        MOVE,
        CHARGE
    }

    public enum Status { PENDING, RUNNING, DONE }

    public Leg {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    Leg with(Status newStatus) {
        return new Leg(kind, point, action, parameters, newStatus);
    }
}
