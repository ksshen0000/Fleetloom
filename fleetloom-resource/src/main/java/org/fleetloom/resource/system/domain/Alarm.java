package org.fleetloom.resource.system.domain;

import java.time.Instant;
import java.util.Objects;

/** A condition someone should look at; see {@link AlarmStatus} for its life cycle. */
public record Alarm(Long id, String alarmKey, AlarmCategory category, AlarmLevel level, String vehicleCode,
        String orderNo, String message, AlarmStatus status, int occurrences, Instant raisedAt, Instant lastSeenAt,
        String acknowledgedBy, Instant acknowledgedAt, String clearedBy, Instant clearedAt) {

    public static Alarm raise(String key, AlarmCategory category, AlarmLevel level, String vehicleCode, String orderNo,
            String message, Instant now) {
        return new Alarm(null, key, category, level, vehicleCode, orderNo, message, AlarmStatus.ACTIVE, 1, now, now,
                null, null, null, null);
    }

    public boolean open() {
        return status != AlarmStatus.CLEARED;
    }

    /** Whether reporting the condition again with this level and message changes anything. */
    public boolean sameAs(AlarmLevel level, String message) {
        return this.level == level && Objects.equals(this.message, message);
    }

    public Alarm repeated(AlarmLevel level, String message, Instant now) {
        return new Alarm(id, alarmKey, category, level, vehicleCode, orderNo, message, status, occurrences + 1, raisedAt,
                now, acknowledgedBy, acknowledgedAt, clearedBy, clearedAt);
    }

    public Alarm acknowledged(String user, Instant now) {
        return new Alarm(id, alarmKey, category, level, vehicleCode, orderNo, message, AlarmStatus.ACKNOWLEDGED,
                occurrences, raisedAt, lastSeenAt, user, now, clearedBy, clearedAt);
    }

    /** @param user {@code null} when the condition went away by itself */
    public Alarm cleared(String user, Instant now) {
        return new Alarm(id, alarmKey, category, level, vehicleCode, orderNo, message, AlarmStatus.CLEARED,
                occurrences, raisedAt, lastSeenAt, acknowledgedBy, acknowledgedAt, user, now);
    }

    public Alarm withId(long id) {
        return new Alarm(id, alarmKey, category, level, vehicleCode, orderNo, message, status, occurrences, raisedAt,
                lastSeenAt, acknowledgedBy, acknowledgedAt, clearedBy, clearedAt);
    }
}
