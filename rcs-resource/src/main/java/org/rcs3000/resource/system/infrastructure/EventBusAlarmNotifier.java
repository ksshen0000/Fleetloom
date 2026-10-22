package org.rcs3000.resource.system.infrastructure;

import org.rcs3000.common.event.EventBus;
import org.rcs3000.contracts.alarm.AlarmChangedEvent;
import org.rcs3000.contracts.alarm.AlarmDto;
import org.rcs3000.resource.system.domain.Alarm;
import org.rcs3000.resource.system.domain.AlarmNotifier;
import org.springframework.stereotype.Component;

@Component
public class EventBusAlarmNotifier implements AlarmNotifier {

    private final EventBus events;

    public EventBusAlarmNotifier(EventBus events) {
        this.events = events;
    }

    @Override
    public void alarmChanged(Alarm a) {
        events.publish(AlarmChangedEvent.TYPE, new AlarmChangedEvent(new AlarmDto(a.id(), a.alarmKey(), a.category().name(),
                a.level().name(), a.vehicleCode(), a.orderNo(), a.message(), a.status().name(), a.occurrences(),
                a.raisedAt(), a.lastSeenAt(), a.acknowledgedBy(), a.acknowledgedAt(), a.clearedBy(), a.clearedAt())));
    }
}
