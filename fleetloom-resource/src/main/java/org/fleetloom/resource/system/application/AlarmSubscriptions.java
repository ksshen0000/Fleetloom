package org.fleetloom.resource.system.application;

import org.fleetloom.common.event.EventBus;
import org.fleetloom.contracts.dispatch.OrderOverdueEvent;
import org.fleetloom.contracts.dispatch.OrderStatusChangedEvent;
import org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent;
import org.fleetloom.contracts.fleet.VehicleForgottenEvent;
import org.fleetloom.contracts.fleet.VehicleStateUpdatedEvent;
import org.fleetloom.contracts.traffic.DeadlockDetectedEvent;
import org.fleetloom.contracts.traffic.RouteProgressedEvent;
import org.fleetloom.contracts.traffic.TrafficBlockedEvent;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Feeds the events of the other services into {@link AlarmRules}. Vehicle state is consumed live
 * (what piled up while this service was down is stale anyway); the rest is consumed in full.
 */
@Component
@ConditionalOnProperty(name = "fleetloom.alarms.enabled", havingValue = "true", matchIfMissing = true)
public class AlarmSubscriptions implements ApplicationRunner {

    static final String GROUP = "fleetloom-resource-alarms";

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
