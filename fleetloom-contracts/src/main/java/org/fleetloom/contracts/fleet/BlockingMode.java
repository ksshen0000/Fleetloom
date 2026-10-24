package org.fleetloom.contracts.fleet;

/** How an action blocks driving and other actions (VDA 5050 blockingType). */
public enum BlockingMode {
    /** Runs in parallel, the vehicle may drive. */
    NONE,
    /** Runs in parallel with other actions, the vehicle stands still. */
    SOFT,
    /** Runs alone, the vehicle stands still. */
    HARD
}
