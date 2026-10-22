package org.rcs3000.resource.system.application;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.rcs3000.contracts.dispatch.OrderOverdueEvent;
import org.rcs3000.contracts.dispatch.OrderStatus;
import org.rcs3000.contracts.dispatch.OrderStatusChangedEvent;
import org.rcs3000.contracts.dispatch.OrderViewDto;
import org.rcs3000.contracts.fleet.ConnectionStatus;
import org.rcs3000.contracts.fleet.VehicleConnectionChangedEvent;
import org.rcs3000.contracts.fleet.VehicleForgottenEvent;
import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.contracts.fleet.VehicleStateUpdatedEvent;
import org.rcs3000.contracts.traffic.DeadlockDetectedEvent;
import org.rcs3000.contracts.traffic.RouteProgressedEvent;
import org.rcs3000.contracts.traffic.RouteStatus;
import org.rcs3000.contracts.traffic.TrafficBlockedEvent;
import org.rcs3000.resource.system.domain.AlarmCategory;
import org.rcs3000.resource.system.domain.AlarmLevel;
import org.springframework.stereotype.Component;

/** Which platform events open and close which alarms. */
@Component
public class AlarmRules {

    private final AlarmService alarms;

    public AlarmRules(AlarmService alarms) {
        this.alarms = alarms;
    }

    /** Vehicle errors (VEH-08) come and go with the state messages; an ONLINE state ends an offline alarm. */
    public void on(VehicleStateUpdatedEvent event) {
        VehicleStatusDto s = event.status();
        String prefix = key(AlarmCategory.VEHICLE_ERROR, s.code()) + ":";
        Set<String> current = new HashSet<>();
        for (VehicleStatusDto.VehicleError e : s.errors()) {
            String key = prefix + e.errorType();
            current.add(key);
            AlarmLevel level = "FATAL".equals(e.level()) ? AlarmLevel.FATAL : AlarmLevel.WARNING;
            String message = e.description() == null || e.description().isBlank()
                    ? e.errorType() : e.errorType() + ": " + e.description();
            alarms.raise(key, AlarmCategory.VEHICLE_ERROR, level, s.code(), null, message);
        }
        alarms.clearWhere(a -> a.alarmKey().startsWith(prefix) && !current.contains(a.alarmKey()));
        if (s.connection() == ConnectionStatus.ONLINE) {
            alarms.clear(key(AlarmCategory.VEHICLE_OFFLINE, s.code()));
        }
    }

    public void on(VehicleConnectionChangedEvent event) {
        String key = key(AlarmCategory.VEHICLE_OFFLINE, event.vehicleCode());
        if (event.current() == ConnectionStatus.ONLINE) {
            alarms.clear(key);
            return;
        }
        String message = event.current() == ConnectionStatus.OFFLINE ? "vehicle signed off" : "connection lost";
        if (event.reason() != null && !event.reason().isBlank()) {
            message += ": " + event.reason();
        }
        alarms.raise(key, AlarmCategory.VEHICLE_OFFLINE, AlarmLevel.ERROR, event.vehicleCode(), null, message);
    }

    /** A removed vehicle's own alarms no longer need anyone's attention. */
    public void on(VehicleForgottenEvent event) {
        alarms.clearWhere(a -> event.vehicleCode().equals(a.vehicleCode())
                && (a.category() == AlarmCategory.VEHICLE_OFFLINE || a.category() == AlarmCategory.VEHICLE_ERROR));
    }

    public void on(TrafficBlockedEvent event) {
        alarms.raise(key(AlarmCategory.TRAFFIC_BLOCKED, event.vehicleCode()), AlarmCategory.TRAFFIC_BLOCKED,
                AlarmLevel.WARNING, event.vehicleCode(), null,
                "waiting for %s at %s".formatted(event.blockedBy(), event.resource()));
    }

    public void on(DeadlockDetectedEvent event) {
        List<String> cycle = event.vehicleCodes().stream().sorted().toList();
        alarms.raise(key(AlarmCategory.DEADLOCK, String.join(",", cycle)), AlarmCategory.DEADLOCK, AlarmLevel.FATAL,
                cycle.getFirst(), null, "vehicles wait for each other: " + String.join(" -> ", event.vehicleCodes()));
    }

    /** Once a vehicle moves on, it is no longer blocked nor part of a deadlock. */
    public void on(RouteProgressedEvent event) {
        if (event.status() == RouteStatus.WAITING) {
            return;
        }
        String vehicle = event.vehicleCode();
        alarms.clear(key(AlarmCategory.TRAFFIC_BLOCKED, vehicle));
        String deadlock = key(AlarmCategory.DEADLOCK, "");
        alarms.clearWhere(a -> a.alarmKey().startsWith(deadlock)
                && List.of(a.alarmKey().substring(deadlock.length()).split(",")).contains(vehicle));
    }

    /** DSP-10: suspended orders stay alarmed until they move on; failed orders until someone closes them. */
    public void on(OrderStatusChangedEvent event) {
        OrderViewDto o = event.order();
        String suspended = key(AlarmCategory.ORDER_SUSPENDED, o.orderNo());
        if (o.status() == OrderStatus.SUSPENDED) {
            alarms.raise(suspended, AlarmCategory.ORDER_SUSPENDED, AlarmLevel.WARNING, o.assignedVehicle(), o.orderNo(),
                    o.reason() == null ? "order suspended" : o.reason());
        } else {
            alarms.clear(suspended);
        }
        if (o.status() == OrderStatus.COMPLETED || o.status() == OrderStatus.CANCELLED || o.status() == OrderStatus.FAILED
                || o.status() == OrderStatus.REJECTED) {
            alarms.clear(key(AlarmCategory.ORDER_OVERDUE, o.orderNo()));
        }
        if (o.status() == OrderStatus.FAILED) {
            alarms.raise(key(AlarmCategory.ORDER_FAILED, o.orderNo()), AlarmCategory.ORDER_FAILED, AlarmLevel.ERROR,
                    o.assignedVehicle(), o.orderNo(), o.reason() == null ? "order failed" : o.reason());
        }
    }

    /** Stays until the order finishes one way or another. */
    public void on(OrderOverdueEvent event) {
        OrderViewDto o = event.order();
        alarms.raise(key(AlarmCategory.ORDER_OVERDUE, o.orderNo()), AlarmCategory.ORDER_OVERDUE, AlarmLevel.WARNING,
                o.assignedVehicle(), o.orderNo(), "not finished by its deadline " + o.deadline());
    }

    static String key(AlarmCategory category, String subject) {
        return category.name() + ":" + subject;
    }
}
