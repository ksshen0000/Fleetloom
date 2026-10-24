package org.fleetloom.resource.system.application;

import java.time.Clock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.fleetloom.common.error.BizException;
import org.fleetloom.resource.system.domain.Alarm;
import org.fleetloom.resource.system.domain.AlarmCategory;
import org.fleetloom.resource.system.domain.AlarmLevel;
import org.fleetloom.resource.system.domain.AlarmNotifier;
import org.fleetloom.resource.system.domain.AlarmRepository;
import org.fleetloom.resource.system.domain.AlarmStatus;
import org.fleetloom.resource.system.domain.SystemErrorCode;
import org.springframework.stereotype.Service;

/**
 * Raises and clears alarms by condition key. Conditions are reported over and over (vehicle errors
 * come with every state message), so the open alarms are kept in memory and the database is only
 * touched when something actually changes. Serialised, since events arrive on several threads.
 */
@Service
public class AlarmService {

    static final int MAX_LIMIT = 1000;

    private final AlarmRepository alarms;
    private final AlarmNotifier notifier;
    private final Clock clock;
    private Map<String, Alarm> open;

    public AlarmService(AlarmRepository alarms, AlarmNotifier notifier, Clock clock) {
        this.alarms = alarms;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** Opens an alarm for {@code key}, or updates the open one if level or message changed. */
    public synchronized void raise(String key, AlarmCategory category, AlarmLevel level, String vehicleCode,
            String orderNo, String message) {
        Alarm existing = open().get(key);
        Alarm alarm;
        if (existing == null) {
            alarm = Alarm.raise(key, category, level, vehicleCode, orderNo, message, clock.instant());
        } else if (existing.sameAs(level, message)) {
            return;
        } else {
            alarm = existing.repeated(level, message, clock.instant());
        }
        store(alarm);
    }

    /** The condition behind {@code key} went away. */
    public synchronized void clear(String key) {
        Alarm existing = open().get(key);
        if (existing != null) {
            store(existing.cleared(null, clock.instant()));
        }
    }

    /** Clears every open alarm matching {@code condition}. */
    public synchronized void clearWhere(Predicate<Alarm> condition) {
        List<Alarm> matching = open().values().stream().filter(condition).toList();
        matching.forEach(a -> store(a.cleared(null, clock.instant())));
    }

    public synchronized Alarm acknowledge(long id, String user) {
        Alarm alarm = require(id);
        if (alarm.status() != AlarmStatus.ACTIVE) {
            return alarm;
        }
        return store(alarm.acknowledged(user, clock.instant()));
    }

    /** Closes an alarm by hand, e.g. a failed order that has been dealt with. */
    public synchronized Alarm close(long id, String user) {
        Alarm alarm = require(id);
        if (!alarm.open()) {
            return alarm;
        }
        return store(alarm.cleared(user, clock.instant()));
    }

    public List<Alarm> list(Boolean open, AlarmLevel level, String vehicleCode, int limit) {
        return alarms.find(open, level, vehicleCode, Math.clamp(limit, 1, MAX_LIMIT));
    }

    public synchronized Summary summary() {
        Map<AlarmLevel, Integer> byLevel = new EnumMap<>(AlarmLevel.class);
        int unacknowledged = 0;
        for (Alarm a : open().values()) {
            byLevel.merge(a.level(), 1, Integer::sum);
            if (a.status() == AlarmStatus.ACTIVE) {
                unacknowledged++;
            }
        }
        return new Summary(open().size(), unacknowledged, byLevel);
    }

    /** @param open open alarms, {@code unacknowledged} of them not yet acknowledged */
    public record Summary(int open, int unacknowledged, Map<AlarmLevel, Integer> byLevel) {
    }

    private Alarm require(long id) {
        return alarms.findById(id)
                .orElseThrow(() -> new BizException(SystemErrorCode.ALARM_NOT_FOUND, "alarm %d not found".formatted(id)));
    }

    private Alarm store(Alarm alarm) {
        Alarm saved = alarms.save(alarm);
        if (saved.open()) {
            open().put(saved.alarmKey(), saved);
        } else {
            open().remove(saved.alarmKey());
        }
        notifier.alarmChanged(saved);
        return saved;
    }

    private Map<String, Alarm> open() {
        if (open == null) {
            open = new HashMap<>();
            alarms.findOpen().forEach(a -> open.put(a.alarmKey(), a));
        }
        return open;
    }
}
