package org.rcs3000.resource.map.domain;

import org.rcs3000.contracts.map.MapPublishedEvent;

/** Outbound port for map domain events. */
public interface MapEventPublisher {

    void mapPublished(MapPublishedEvent event);
}
