package org.rcs3000.resource.map.infrastructure.event;

import org.rcs3000.contracts.map.MapPublishedEvent;
import org.rcs3000.resource.map.domain.MapEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publishes map events inside this process. Will be backed by the inter-service event bus
 * (Valkey Streams) once it is introduced.
 */
@Component
public class SpringMapEventPublisher implements MapEventPublisher {

    private final ApplicationEventPublisher publisher;

    public SpringMapEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void mapPublished(MapPublishedEvent event) {
        publisher.publishEvent(event);
    }
}
