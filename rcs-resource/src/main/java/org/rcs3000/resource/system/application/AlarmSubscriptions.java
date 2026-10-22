package org.rcs3000.resource.system.application;

import org.rcs3000.common.event.EventBus;
import org.rcs3000.contracts.dispatch.OrderOverdueEvent;
import org.rcs3000.contracts.dispatch.OrderStatusChangedEvent;
import org.rcs3000.contracts.fleet.VehicleConnectionChangedEvent;
import org.rcs3000.contracts.fleet.VehicleForgottenEvent;
import org.rcs3000.contracts.fleet.VehicleStateUpdatedEvent;
import org.rcs3000.contracts.traffic.DeadlockDetectedEvent;
import org.rcs3000.contracts.traffic.RouteProgressedEvent;
import org.rcs3000.contracts.traffic.TrafficBlockedEvent;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feeds the events of the other services into {@link AlarmRules}. Vehicle state is consumed live
 * (what piled up while this service was down is stale anyway); the rest is consumed in full.
 */
@Component
@ConditionalOnProperty(name = "rcs.alarms.enabled", havingValue = "true", matchIfMissing = true)
public class AlarmSubscriptions implements ApplicationRunner {

    static final String GROUP = "rcs-resource-alarms";

    private final EventBus events;
    private final AlarmRules rules;

    public AlarmSubscriptions(EventBus events, AlarmRules rules) {
        this.events = events;
        this.rules = rules;
    }

    @Override
    public void run(ApplicationArguments args) {
        events.subscribeLive(VehicleStateUpdatedEvent.TYPE, GROUP, VehicleStateUpdatedEvent.class, e -> rules.on(e.payload()));
        events.subscribe(VehicleConnectionChangedEvent.TYPE, GROUP, VehicleConnectionChangedEvent.class, e -> rules.on(e.payload()));
        events.subscribe(VehicleForgottenEvent.TYPE, GROUP, VehicleForgottenEvent.class, e -> rules.on(e.payload()));
        events.subscribe(TrafficBlockedEvent.TYPE, GROUP, TrafficBlockedEvent.class, e -> rules.on(e.payload()));
        events.subscribe(DeadlockDetectedEvent.TYPE, GROUP, DeadlockDetectedEvent.class, e -> rules.on(e.payload()));
        events.subscribe(RouteProgressedEvent.TYPE, GROUP, RouteProgressedEvent.class, e -> rules.on(e.payload()));
        events.subscribe(OrderStatusChangedEvent.TYPE, GROUP, OrderStatusChangedEvent.class, e -> rules.on(e.payload()));
        events.subscribe(OrderOverdueEvent.TYPE, GROUP, OrderOverdueEvent.class, e -> rules.on(e.payload()));
    }
}
