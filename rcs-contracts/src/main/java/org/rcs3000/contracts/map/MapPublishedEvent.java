package org.rcs3000.contracts.map;

import java.time.Instant;

/** Emitted when a map version becomes the active version of its map. */
public record MapPublishedEvent(String mapCode, int versionNo, Instant publishedAt) {

    public static final String TYPE = "MapPublished";
}
