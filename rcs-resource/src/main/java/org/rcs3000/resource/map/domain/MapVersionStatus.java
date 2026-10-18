package org.rcs3000.resource.map.domain;

public enum MapVersionStatus {
    /** Editable; at most one per map. */
    DRAFT,
    /** The active version of its map. Immutable. */
    PUBLISHED,
    /** A previously published version. Immutable. */
    ARCHIVED
}
