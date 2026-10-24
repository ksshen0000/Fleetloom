package org.fleetloom.resource.map.domain;

import org.fleetloom.contracts.map.MapPublishedEvent;

/** Outbound port for map domain events. */
public interface MapEventPublisher {

    void mapPublished(MapPublishedEvent event);
}
