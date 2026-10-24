package org.fleetloom.contracts.dispatch;

/** Emitted on every order status change (requirement DSP-11 builds on it). */
public record OrderStatusChangedEvent(OrderViewDto order, OrderStatus previous) {

    public static final String TYPE = "TransportOrderStatusChanged";
}
