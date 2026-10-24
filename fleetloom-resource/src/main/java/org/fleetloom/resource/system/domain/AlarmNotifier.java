package org.fleetloom.resource.system.domain;

/** Tells the rest of the platform (and through the gateway, the browsers) that an alarm changed. */
public interface AlarmNotifier {

    void alarmChanged(Alarm alarm);
}
