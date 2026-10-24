package org.fleetloom.contracts.map;

public enum PointType {
    /** Ordinary waypoint. */
    NORMAL,
    /** Pick/drop point bound to a storage location. */
    WORKSTATION,
    CHARGING,
    PARKING,
    /** Vehicles may rotate in place here. */
    ROTATION,
    /** Side point used to give way during deadlock resolution. */
    WAITING
}
