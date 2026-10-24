package org.fleetloom.resource.system.domain;

import java.time.Instant;

/** A console user. {@code passwordHash} never leaves the service. */
public record User(Long id, String username, String displayName, String passwordHash, Role role, boolean enabled,
        Instant createdAt, Instant updatedAt) {

    public User with(String displayName, Role role, boolean enabled, Instant now) {
        return new User(id, username, displayName, passwordHash, role, enabled, createdAt, now);
    }

    public User withPasswordHash(String hash, Instant now) {
        return new User(id, username, displayName, hash, role, enabled, createdAt, now);
    }
}
