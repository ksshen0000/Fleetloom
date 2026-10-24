package org.fleetloom.resource.map.application;

import java.time.Instant;

import org.fleetloom.resource.map.domain.SiteMapDefinition;

public record MapView(String code, String name, String description, Integer activeVersionNo,
        Instant createdAt, Instant updatedAt) {

    static MapView of(SiteMapDefinition map) {
        return new MapView(map.getCode(), map.getName(), map.getDescription(), map.getActiveVersionNo(),
                map.getCreatedAt(), map.getUpdatedAt());
    }
}
