package org.fleetloom.resource.map.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.fleetloom.common.error.BizException;
import org.fleetloom.contracts.map.MapPublishedEvent;
import org.fleetloom.resource.map.domain.MapErrorCode;
import org.fleetloom.resource.map.domain.MapEventPublisher;
import org.fleetloom.resource.map.domain.MapRepository;
import org.fleetloom.resource.map.domain.MapVersion;
import org.fleetloom.resource.map.domain.MapVersionRepository;
import org.fleetloom.resource.map.domain.MapVersionStatus;
import org.fleetloom.resource.map.domain.MapVersionSummary;
import org.fleetloom.resource.map.domain.SiteMapDefinition;

/**
 * In-memory implementations of the map ports. They store snapshots, not live references, so that a
 * test notices when the service forgets to persist a change, and they enforce the same constraints
 * as the database (unique code, one draft / one published version, optimistic revision).
 */
final class InMemoryMapRepositories {

    private InMemoryMapRepositories() {
    }

    static final class Maps implements MapRepository {

        private final Map<String, SiteMapDefinition> byCode = new HashMap<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public Optional<SiteMapDefinition> findByCode(String code) {
            return Optional.ofNullable(byCode.get(code)).map(Maps::copy);
        }

        @Override
        public boolean existsByCode(String code) {
            return byCode.containsKey(code);
        }

        @Override
        public List<SiteMapDefinition> findAll() {
            return byCode.values().stream().sorted(Comparator.comparing(SiteMapDefinition::getCode)).map(Maps::copy).toList();
        }

        @Override
        public void save(SiteMapDefinition map) {
            if (map.getId() == null) {
                map.assignId(ids.incrementAndGet());
            }
            byCode.put(map.getCode(), copy(map));
        }

        @Override
        public void delete(SiteMapDefinition map) {
            byCode.remove(map.getCode());
        }

        private static SiteMapDefinition copy(SiteMapDefinition m) {
            return new SiteMapDefinition(m.getId(), m.getCode(), m.getName(), m.getDescription(), m.getActiveVersionNo(),
                    m.getCreatedAt(), m.getUpdatedAt());
        }
    }

    static final class Versions implements MapVersionRepository {

        private final Map<Long, MapVersion> byId = new HashMap<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public Optional<MapVersion> find(Long mapId, int versionNo) {
            return byId.values().stream()
                    .filter(v -> v.getMapId().equals(mapId) && v.getVersionNo() == versionNo)
                    .findFirst()
                    .map(Versions::copy);
        }

        @Override
        public Optional<MapVersion> findDraft(Long mapId) {
            return byId.values().stream()
                    .filter(v -> v.getMapId().equals(mapId) && v.getStatus() == MapVersionStatus.DRAFT)
                    .findFirst()
                    .map(Versions::copy);
        }

        @Override
        public List<MapVersionSummary> listSummaries(Long mapId) {
            return byId.values().stream()
                    .filter(v -> v.getMapId().equals(mapId))
                    .sorted(Comparator.comparingInt(MapVersion::getVersionNo).reversed())
                    .map(v -> new MapVersionSummary(v.getVersionNo(), v.getStatus(), v.getRevision(), v.getCreatedAt(),
                            v.getUpdatedAt(), v.getPublishedAt(), v.getPublishedBy()))
                    .toList();
        }

        @Override
        public int maxVersionNo(Long mapId) {
            return byId.values().stream()
                    .filter(v -> v.getMapId().equals(mapId))
                    .mapToInt(MapVersion::getVersionNo)
                    .max()
                    .orElse(0);
        }

        @Override
        public void insert(MapVersion version) {
            version.assignId(ids.incrementAndGet());
            store(version);
        }

        @Override
        public void update(MapVersion version, int previousRevision) {
            MapVersion stored = byId.get(version.getId());
            if (stored == null || stored.getRevision() != previousRevision) {
                throw new BizException(MapErrorCode.MAP_REVISION_CONFLICT, "concurrent modification");
            }
            store(version);
        }

        @Override
        public void delete(MapVersion version) {
            byId.remove(version.getId());
        }

        private void store(MapVersion version) {
            if (version.getStatus() != MapVersionStatus.ARCHIVED) {
                boolean clash = byId.values().stream().anyMatch(v -> !v.getId().equals(version.getId())
                        && v.getMapId().equals(version.getMapId()) && v.getStatus() == version.getStatus());
                if (clash) {
                    throw new IllegalStateException("unique index violated: second " + version.getStatus());
                }
            }
            byId.put(version.getId(), copy(version));
        }

        private static MapVersion copy(MapVersion v) {
            return new MapVersion(v.getId(), v.getMapId(), v.getVersionNo(), v.getStatus(), v.getContent(),
                    v.getRevision(), v.getCreatedAt(), v.getUpdatedAt(), v.getPublishedAt(), v.getPublishedBy());
        }
    }

    static final class RecordingEvents implements MapEventPublisher {

        final List<MapPublishedEvent> published = new ArrayList<>();

        @Override
        public void mapPublished(MapPublishedEvent event) {
            published.add(event);
        }
    }
}
