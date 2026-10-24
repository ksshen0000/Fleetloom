package org.fleetloom.resource.map.infrastructure.persistence;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("map_version")
public class MapVersionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long mapId;
    private Integer versionNo;
    private String status;
    /** JSON document of {@code MapContent}, stored as jsonb. */
    private String content;
    private Integer revision;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant publishedAt;
    private String publishedBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMapId() {
        return mapId;
    }

    public void setMapId(Long mapId) {
        this.mapId = mapId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getRevision() {
        return revision;
    }

    public void setRevision(Integer revision) {
        this.revision = revision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String publishedBy) {
        this.publishedBy = publishedBy;
    }
}
