package org.rcs3000.resource.map.application;

import java.time.Instant;

import org.rcs3000.resource.map.domain.RcsMap;

public record MapView(String code, String name, String description, Integer activeVersionNo,
        Instant createdAt, Instant updatedAt) {

    static MapView of(RcsMap map) {
        return new MapView(map.getCode(), map.getName(), map.getDescription(), map.getActiveVersionNo(),
                map.getCreatedAt(), map.getUpdatedAt());
    }
}
