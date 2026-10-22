package org.rcs3000.resource.system.domain;

public enum AlarmStatus {
    /** Open and nobody has acknowledged it yet. */
    ACTIVE,
    /** Open; someone has seen it and is handling it. */
    ACKNOWLEDGED,
    /** The condition went away, or someone closed the alarm by hand. */
    CLEARED
}
