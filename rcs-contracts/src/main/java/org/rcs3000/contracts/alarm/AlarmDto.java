package org.rcs3000.contracts.alarm;

import java.time.Instant;

/**
 * A platform alarm (requirements VEH-08, DSP-10, WEB-08).
 *
 * @param alarmKey    identifies the condition, e.g. {@code VEHICLE_OFFLINE:AGV-1}; at most one open alarm per key
 * @param level       WARNING, ERROR or FATAL
 * @param status      ACTIVE (open, nobody has looked), ACKNOWLEDGED (open, someone is on it) or CLEARED
 * @param occurrences how often the condition was reported while the alarm was open
 */
public record AlarmDto(long id, String alarmKey, String category, String level, String vehicleCode, String orderNo,
        String message, String status, int occurrences, Instant raisedAt, Instant lastSeenAt,
        String acknowledgedBy, Instant acknowledgedAt, String clearedBy, Instant clearedAt) {
}
