package org.rcs3000.resource.map.infrastructure.event;

import org.rcs3000.common.event.EventBus;
import org.rcs3000.contracts.map.MapPublishedEvent;
import org.rcs3000.resource.map.domain.MapEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes map events on the inter-service event bus. Events are sent after the transaction
 * commits, so consumers never see a version that was rolled back.
 */
@Component
public class EventBusMapEventPublisher implements MapEventPublisher {

    private final EventBus events;

    public EventBusMapEventPublisher(EventBus events) {
        this.events = events;
    }

    @Override
    public void mapPublished(MapPublishedEvent event) {
        Runnable publish = () -> events.publish(MapPublishedEvent.TYPE, event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
