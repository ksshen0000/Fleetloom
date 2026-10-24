package org.fleetloom.resource.storage.domain;

import java.time.Instant;

/** A logical group of storage locations, e.g. raw material or finished goods. */
public record Zone(Long id, String code, String name, String type, String description, Instant createdAt,
        Instant updatedAt) {

    public Zone withDetails(String name, String type, String description, Instant now) {
        return new Zone(id, code, name, type, description, createdAt, now);
    }
}
