package org.fleetloom.resource.map.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.fleetloom.common.error.BizException;
import org.fleetloom.resource.map.domain.MapContent;
import org.fleetloom.resource.map.domain.MapErrorCode;
import org.fleetloom.resource.map.domain.MapVersion;
import org.fleetloom.resource.map.domain.MapVersionRepository;
import org.fleetloom.resource.map.domain.MapVersionStatus;
import org.fleetloom.resource.map.domain.MapVersionSummary;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class MybatisMapVersionRepository implements MapVersionRepository {

    private final MapVersionMapper mapper;
    private final JsonMapper json;

    public MybatisMapVersionRepository(MapVersionMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Optional<MapVersion> find(Long mapId, int versionNo) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<MapVersionEntity>()
                        .eq(MapVersionEntity::getMapId, mapId)
                        .eq(MapVersionEntity::getVersionNo, versionNo)))
                .map(this::toDomain);
    }

    @Override
    public Optional<MapVersion> findDraft(Long mapId) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<MapVersionEntity>()
                        .eq(MapVersionEntity::getMapId, mapId)
                        .eq(MapVersionEntity::getStatus, MapVersionStatus.DRAFT.name())))
                .map(this::toDomain);
    }

    @Override
    public List<MapVersionSummary> listSummaries(Long mapId) {
        return mapper.selectList(new LambdaQueryWrapper<MapVersionEntity>()
                        .select(MapVersionEntity::getVersionNo, MapVersionEntity::getStatus, MapVersionEntity::getRevision,
                                MapVersionEntity::getCreatedAt, MapVersionEntity::getUpdatedAt,
                                MapVersionEntity::getPublishedAt, MapVersionEntity::getPublishedBy)
                        .eq(MapVersionEntity::getMapId, mapId)
                        .orderByDesc(MapVersionEntity::getVersionNo))
                .stream()
                .map(e -> new MapVersionSummary(e.getVersionNo(), MapVersionStatus.valueOf(e.getStatus()), e.getRevision(),
                        e.getCreatedAt(), e.getUpdatedAt(), e.getPublishedAt(), e.getPublishedBy()))
                .toList();
    }

    @Override
    public int maxVersionNo(Long mapId) {
        return mapper.maxVersionNo(mapId);
    }

    @Override
    public void insert(MapVersion version) {
        MapVersionEntity entity = toEntity(version);
        mapper.insert(entity);
        version.assignId(entity.getId());
    }

    @Override
    public void update(MapVersion version, int previousRevision) {
        MapVersionEntity entity = toEntity(version);
        int updated = mapper.update(entity, new LambdaUpdateWrapper<MapVersionEntity>()
                .eq(MapVersionEntity::getId, version.getId())
                .eq(MapVersionEntity::getRevision, previousRevision));
        if (updated != 1) {
            throw new BizException(MapErrorCode.MAP_REVISION_CONFLICT,
                    "map version %d was modified concurrently".formatted(version.getVersionNo()));
        }
    }

    @Override
    public void delete(MapVersion version) {
        mapper.deleteById(version.getId());
    }

    private MapVersion toDomain(MapVersionEntity e) {
        return new MapVersion(e.getId(), e.getMapId(), e.getVersionNo(), MapVersionStatus.valueOf(e.getStatus()),
                json.readValue(e.getContent(), MapContent.class), e.getRevision(),
                e.getCreatedAt(), e.getUpdatedAt(), e.getPublishedAt(), e.getPublishedBy());
    }

    private MapVersionEntity toEntity(MapVersion v) {
        MapVersionEntity e = new MapVersionEntity();
        e.setId(v.getId());
        e.setMapId(v.getMapId());
        e.setVersionNo(v.getVersionNo());
        e.setStatus(v.getStatus().name());
        e.setContent(json.writeValueAsString(v.getContent()));
        e.setRevision(v.getRevision());
        e.setCreatedAt(v.getCreatedAt());
        e.setUpdatedAt(v.getUpdatedAt());
        e.setPublishedAt(v.getPublishedAt());
        e.setPublishedBy(v.getPublishedBy());
        return e;
    }
}
