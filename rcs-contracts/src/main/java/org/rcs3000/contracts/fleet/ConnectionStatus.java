package org.rcs3000.contracts.fleet;

public enum ConnectionStatus {
    ONLINE,
    /** The vehicle signed off cleanly. */
    OFFLINE,
    /** The connection was lost, or no state arrived within the heartbeat timeout. */
    CONNECTION_BROKEN
}
