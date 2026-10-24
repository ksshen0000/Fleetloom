package org.fleetloom.contracts.dispatch;

public enum OrderType {
    /** Move a carrier from its storage location to another one. */
    TRANSPORT,
    /** Send a vehicle to a map point. */
    MOVE,
    /** Created by the dispatcher: an idle vehicle with a low battery goes charging. */
    CHARGE
}
