package org.rcs3000.resource.map.domain;

import java.time.Instant;
import java.util.Objects;

/** A site map. Its content lives in {@link MapVersion}s; one of them may be active. */
public class RcsMap {

    private Long id;
    private final String code;
    private String name;
    private String description;
    private Integer activeVersionNo;
    private final Instant createdAt;
    private Instant updatedAt;

    public RcsMap(Long id, String code, String name, String description, Integer activeVersionNo,
            Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.code = Objects.requireNonNull(code, "code");
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.activeVersionNo = activeVersionNo;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static RcsMap create(String code, String name, String description, Instant now) {
        return new RcsMap(null, code, name, description, null, now, now);
    }

    public void rename(String name, String description, Instant now) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description;
        this.updatedAt = now;
    }

    void activate(int versionNo, Instant now) {
        this.activeVersionNo = versionNo;
        this.updatedAt = now;
    }

    public boolean hasBeenPublished() {
        return activeVersionNo != null;
    }

    public void assignId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Integer getActiveVersionNo() {
        return activeVersionNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
