package org.fleetloom.contracts.dispatch;

/** An order is still not finished at its deadline (DSP-10); emitted once per order. */
public record OrderOverdueEvent(OrderViewDto order) {

    public static final String TYPE = "TransportOrderOverdue";
}
