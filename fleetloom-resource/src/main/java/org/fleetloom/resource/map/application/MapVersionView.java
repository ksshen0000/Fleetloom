package org.fleetloom.resource.map.application;

import java.time.Instant;
import java.util.List;

import org.fleetloom.contracts.map.AreaDto;
import org.fleetloom.contracts.map.PathDto;
import org.fleetloom.contracts.map.PointDto;
import org.fleetloom.resource.map.domain.MapVersion;
import org.fleetloom.resource.map.domain.MapVersionStatus;

/** A map version with its full content, as edited in the map editor. */
public record MapVersionView(
        String mapCode,
        int versionNo,
        MapVersionStatus status,
        int revision,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        String publishedBy,
        List<PointDto> points,
        List<PathDto> paths,
        List<AreaDto> areas) {

    static MapVersionView of(String mapCode, MapVersion v) {
        return new MapVersionView(mapCode, v.getVersionNo(), v.getStatus(), v.getRevision(),
                v.getCreatedAt(), v.getUpdatedAt(), v.getPublishedAt(), v.getPublishedBy(),
                MapDtoMapper.toPointDtos(v.getContent()),
                MapDtoMapper.toPathDtos(v.getContent()),
                MapDtoMapper.toAreaDtos(v.getContent()));
    }
}
