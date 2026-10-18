package org.rcs3000.resource.map.domain;

import java.time.Instant;

/** A map version without its content, for listings. */
public record MapVersionSummary(
        int versionNo,
        MapVersionStatus status,
        int revision,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        String publishedBy) {
}
