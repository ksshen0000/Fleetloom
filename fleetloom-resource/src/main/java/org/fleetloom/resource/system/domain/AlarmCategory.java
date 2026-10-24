package org.fleetloom.resource.system.domain;

public enum AlarmCategory {
    /** The vehicle reports an error (VDA 5050 errors, VEH-08). */
    VEHICLE_ERROR,
    /** The vehicle signed off or stopped sending state (VEH-07). */
    VEHICLE_OFFLINE,
    /** A vehicle has been waiting for another one longer than the traffic threshold. */
    TRAFFIC_BLOCKED,
    /** Vehicles wait for each other in a cycle (TRF-15). */
    DEADLOCK,
    /** An order was suspended, e.g. because its vehicle went offline (DSP-10). */
    ORDER_SUSPENDED,
    /** An order failed and needs a person to sort out its carrier and locations. */
    ORDER_FAILED,
    /** An order is not finished by its deadline (DSP-10). */
    ORDER_OVERDUE
}
