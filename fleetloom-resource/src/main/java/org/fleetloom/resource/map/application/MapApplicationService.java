package org.fleetloom.resource.map.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.fleetloom.common.error.BizException;
import org.fleetloom.contracts.map.MapPublishedEvent;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.fleetloom.resource.map.domain.MapContent;
import org.fleetloom.resource.map.domain.MapErrorCode;
import org.fleetloom.resource.map.domain.MapEventPublisher;
import org.fleetloom.resource.map.domain.MapRepository;
import org.fleetloom.resource.map.domain.MapValidator;
import org.fleetloom.resource.map.domain.MapVersion;
import org.fleetloom.resource.map.domain.MapVersionRepository;
import org.fleetloom.resource.map.domain.MapVersionSummary;
import org.fleetloom.resource.map.domain.SiteMapDefinition;
import org.fleetloom.resource.map.domain.ValidationReport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use cases of map management (requirements MAP-01, MAP-03, MAP-06, MAP-07, MAP-08, MAP-10). */
@Service
@Transactional
public class MapApplicationService {

    private final MapRepository maps;
    private final MapVersionRepository versions;
    private final MapEventPublisher events;
    private final MapValidator validator = new MapValidator();
    private final Clock clock;

    public MapApplicationService(MapRepository maps, MapVersionRepository versions, MapEventPublisher events,
            Clock clock) {
        this.maps = maps;
        this.versions = versions;
        this.events = events;
        this.clock = clock;
    }

    public MapView createMap(String code, String name, String description) {
        if (maps.existsByCode(code)) {
            throw new BizException(MapErrorCode.MAP_CODE_DUPLICATE, "map '%s' already exists".formatted(code));
        }
        SiteMapDefinition map = SiteMapDefinition.create(code, name, description, now());
        maps.save(map);
        return MapView.of(map);
    }

    @Transactional(readOnly = true)
    public List<MapView> listMaps() {
        return maps.findAll().stream().map(MapView::of).toList();
    }

    @Transactional(readOnly = true)
    public MapView getMap(String code) {
        return MapView.of(requireMap(code));
    }

    public MapView updateMap(String code, String name, String description) {
        SiteMapDefinition map = requireMap(code);
        map.rename(name, description, now());
        maps.save(map);
        return MapView.of(map);
    }

    /** Deletes a map that has never been published; published maps may be referenced by storage locations. */
    public void deleteMap(String code) {
        SiteMapDefinition map = requireMap(code);
        if (map.hasBeenPublished()) {
            throw new BizException(MapErrorCode.MAP_IN_USE,
                    "map '%s' has been published and cannot be deleted".formatted(code));
        }
        maps.delete(map);
    }

    /**
     * Starts a new draft, either empty or as a copy of an existing version (MAP-08).
     *
     * @param copyFromVersionNo version to copy, or {@code null} for an empty draft
     */
    public MapVersionView createDraft(String code, Integer copyFromVersionNo) {
        SiteMapDefinition map = requireMap(code);
        versions.findDraft(map.getId()).ifPresent(draft -> {
            throw new BizException(MapErrorCode.MAP_DRAFT_ALREADY_EXISTS,
                    "map '%s' already has draft version %d".formatted(code, draft.getVersionNo()));
        });
        MapContent content = copyFromVersionNo == null
                ? MapContent.empty()
                : requireVersion(map, copyFromVersionNo).getContent();
        MapVersion draft = MapVersion.newDraft(map.getId(), versions.maxVersionNo(map.getId()) + 1, content, now());
        versions.insert(draft);
        return MapVersionView.of(code, draft);
    }

    @Transactional(readOnly = true)
    public List<MapVersionSummary> listVersions(String code) {
        return versions.listSummaries(requireMap(code).getId());
    }

    @Transactional(readOnly = true)
    public MapVersionView getVersion(String code, int versionNo) {
        return MapVersionView.of(code, requireVersion(requireMap(code), versionNo));
    }

    public SaveDraftResult saveDraftContent(String code, int versionNo, MapContent content, int expectedRevision) {
        MapVersion draft = requireVersion(requireMap(code), versionNo);
        int previousRevision = draft.getRevision();
        draft.replaceContent(content, expectedRevision, now());
        versions.update(draft, previousRevision);
        return new SaveDraftResult(draft.getRevision(), validator.validate(content));
    }

    public void deleteDraft(String code, int versionNo) {
        MapVersion draft = requireVersion(requireMap(code), versionNo);
        draft.requireDraft();
        versions.delete(draft);
    }

    @Transactional(readOnly = true)
    public ValidationReport validate(String code, int versionNo) {
        return validator.validate(requireVersion(requireMap(code), versionNo).getContent());
    }

    /**
     * Publishes a draft (MAP-07): validation errors block it, the previously active version is
     * archived, and a {@link MapPublishedEvent} is emitted.
     *
     * @return the validation report, which may still contain warnings
     */
    public ValidationReport publish(String code, int versionNo, String publishedBy) {
        SiteMapDefinition map = requireMap(code);
        MapVersion draft = requireVersion(map, versionNo);
        draft.requireDraft();
        ValidationReport report = validator.validate(draft.getContent());
        if (report.hasErrors()) {
            throw new BizException(MapErrorCode.MAP_VALIDATION_FAILED,
                    "map version %d has %d validation error(s)".formatted(versionNo, report.errors().size()), report);
        }
        Instant now = now();
        if (map.getActiveVersionNo() != null) {
            MapVersion previous = requireVersion(map, map.getActiveVersionNo());
            int previousRevision = previous.getRevision();
            previous.archive(now);
            versions.update(previous, previousRevision);
        }
        int draftRevision = draft.getRevision();
        draft.publish(map, publishedBy, now);
        versions.update(draft, draftRevision);
        maps.save(map);
        events.mapPublished(new MapPublishedEvent(code, versionNo, now));
        return report;
    }

    /** Topology of the active version, consumed by traffic, fleet and simulator (MAP-10). */
    @Transactional(readOnly = true)
    public MapTopologyDto getActiveTopology(String code) {
        SiteMapDefinition map = requireMap(code);
        if (map.getActiveVersionNo() == null) {
            throw new BizException(MapErrorCode.MAP_NO_ACTIVE_VERSION, "map '%s' has no published version".formatted(code));
        }
        return MapDtoMapper.toTopology(code, requireVersion(map, map.getActiveVersionNo()));
    }

    /**
     * A point of the map's active version; empty if the map has no published version or not this point.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<org.fleetloom.contracts.map.PointDto> findActivePoint(String mapCode, String pointCode) {
        return maps.findByCode(mapCode)
                .filter(SiteMapDefinition::hasBeenPublished)
                .flatMap(m -> versions.find(m.getId(), m.getActiveVersionNo()))
                .flatMap(v -> v.getContent().points().stream().filter(p -> p.code().equals(pointCode)).findFirst())
                .map(p -> new org.fleetloom.contracts.map.PointDto(p.code(), p.x(), p.y(), p.theta(), p.type(), p.properties()));
    }

    /** Whether the map exists and has a published version. */
    @Transactional(readOnly = true)
    public boolean isPublished(String mapCode) {
        return maps.findByCode(mapCode).map(SiteMapDefinition::hasBeenPublished).orElse(false);
    }

    private SiteMapDefinition requireMap(String code) {
        return maps.findByCode(code)
                .orElseThrow(() -> new BizException(MapErrorCode.MAP_NOT_FOUND, "map '%s' not found".formatted(code)));
    }

    private MapVersion requireVersion(SiteMapDefinition map, int versionNo) {
        return versions.find(map.getId(), versionNo)
                .orElseThrow(() -> new BizException(MapErrorCode.MAP_VERSION_NOT_FOUND,
                        "map '%s' has no version %d".formatted(map.getCode(), versionNo)));
    }

    private Instant now() {
        return clock.instant();
    }
}
