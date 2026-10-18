package org.rcs3000.resource.map.domain;

import java.time.Instant;
import java.util.Objects;

import org.rcs3000.common.error.BizException;

/**
 * One version of a map. Only a {@link MapVersionStatus#DRAFT} can be edited; publishing freezes it.
 *
 * <p>{@code revision} increments on every content save so that two editors cannot silently overwrite
 * each other: a save must state the revision it was based on.
 */
public class MapVersion {

    private Long id;
    private final Long mapId;
    private final int versionNo;
    private MapVersionStatus status;
    private MapContent content;
    private int revision;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
    private String publishedBy;

    public MapVersion(Long id, Long mapId, int versionNo, MapVersionStatus status, MapContent content,
            int revision, Instant createdAt, Instant updatedAt, Instant publishedAt, String publishedBy) {
        this.id = id;
        this.mapId = Objects.requireNonNull(mapId, "mapId");
        this.versionNo = versionNo;
        this.status = Objects.requireNonNull(status, "status");
        this.content = Objects.requireNonNull(content, "content");
        this.revision = revision;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.publishedAt = publishedAt;
        this.publishedBy = publishedBy;
    }

    public static MapVersion newDraft(Long mapId, int versionNo, MapContent content, Instant now) {
        return new MapVersion(null, mapId, versionNo, MapVersionStatus.DRAFT, content, 0, now, now, null, null);
    }

    public void replaceContent(MapContent newContent, int expectedRevision, Instant now) {
        requireDraft();
        if (expectedRevision != revision) {
            throw new BizException(MapErrorCode.MAP_REVISION_CONFLICT,
                    "map version was modified by someone else (expected revision %d, current %d)"
                            .formatted(expectedRevision, revision));
        }
        this.content = Objects.requireNonNull(newContent, "content");
        this.revision++;
        this.updatedAt = now;
    }

    /**
     * Publishes this draft as the active version of {@code map}. The caller archives the previously
     * active version.
     */
    public void publish(RcsMap map, String publishedBy, Instant now) {
        requireDraft();
        if (!map.getId().equals(mapId)) {
            throw new IllegalArgumentException("version %d does not belong to map %s".formatted(versionNo, map.getCode()));
        }
        this.status = MapVersionStatus.PUBLISHED;
        this.publishedAt = now;
        this.publishedBy = publishedBy;
        this.updatedAt = now;
        map.activate(versionNo, now);
    }

    public void archive(Instant now) {
        if (status != MapVersionStatus.PUBLISHED) {
            throw new IllegalStateException("only a published version can be archived, version %d is %s"
                    .formatted(versionNo, status));
        }
        this.status = MapVersionStatus.ARCHIVED;
        this.updatedAt = now;
    }

    public void requireDraft() {
        if (status != MapVersionStatus.DRAFT) {
            throw new BizException(MapErrorCode.MAP_VERSION_NOT_DRAFT,
                    "map version %d is %s and cannot be modified".formatted(versionNo, status));
        }
    }

    public void assignId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Long getMapId() {
        return mapId;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public MapVersionStatus getStatus() {
        return status;
    }

    public MapContent getContent() {
        return content;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }
}
