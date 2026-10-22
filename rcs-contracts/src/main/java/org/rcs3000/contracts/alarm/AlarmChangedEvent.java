package org.rcs3000.contracts.alarm;

/** Emitted when an alarm is raised, changes, is acknowledged or cleared. */
public record AlarmChangedEvent(AlarmDto alarm) {

    public static final String TYPE = "AlarmChanged";
}
