package org.rcs3000.contracts.map;

public enum AreaType {
    /** Only one vehicle may be inside at a time. */
    MUTEX,
    SPEED_LIMIT,
    FORBIDDEN,
    CHARGING_ZONE
}
