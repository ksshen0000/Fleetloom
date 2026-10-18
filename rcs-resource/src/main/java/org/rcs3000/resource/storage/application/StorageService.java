package org.rcs3000.resource.storage.application;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.rcs3000.common.error.BizException;
import org.rcs3000.contracts.map.PointDto;
import org.rcs3000.resource.map.application.MapApplicationService;
import org.rcs3000.resource.storage.application.StorageViews.LocationDefinition;
import org.rcs3000.resource.storage.application.StorageViews.LocationView;
import org.rcs3000.resource.storage.application.StorageViews.ZoneView;
import org.rcs3000.resource.storage.domain.LocationFilter;
import org.rcs3000.resource.storage.domain.StorageErrorCode;
import org.rcs3000.resource.storage.domain.StorageLocation;
import org.rcs3000.resource.storage.domain.StorageLocationRepository;
import org.rcs3000.resource.storage.domain.StorageLocationStatus;
import org.rcs3000.resource.storage.domain.Zone;
import org.rcs3000.resource.storage.domain.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Zones and storage locations (requirements STO-01..06). */
@Service
@Transactional
public class StorageService {

    private final ZoneRepository zones;
    private final StorageLocationRepository locations;
    private final MapApplicationService maps;
    private final Clock clock;

    public StorageService(ZoneRepository zones, StorageLocationRepository locations, MapApplicationService maps, Clock clock) {
        this.zones = zones;
        this.locations = locations;
        this.maps = maps;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ zones

    public ZoneView createZone(String code, String name, String type, String description) {
        if (zones.findByCode(code).isPresent()) {
            throw new BizException(StorageErrorCode.ZONE_CODE_DUPLICATE, "zone %s already exists".formatted(code));
        }
        return ZoneView.of(zones.save(new Zone(null, code, name, type, description, clock.instant(), clock.instant())));
    }

    public ZoneView updateZone(String code, String name, String type, String description) {
        return ZoneView.of(zones.save(requireZone(code).withDetails(name, type, description, clock.instant())));
    }

    public void deleteZone(String code) {
        Zone zone = requireZone(code);
        if (locations.existsInZone(code)) {
            throw new BizException(StorageErrorCode.ZONE_IN_USE, "zone %s still has storage locations".formatted(code));
        }
        zones.delete(zone);
    }

    @Transactional(readOnly = true)
    public List<ZoneView> listZones() {
        return zones.findAll().stream().map(ZoneView::of).toList();
    }

    // ------------------------------------------------------------------ locations

    public LocationView createLocation(LocationDefinition d) {
        if (locations.findByCode(d.code()).isPresent()) {
            throw new BizException(StorageErrorCode.LOCATION_CODE_DUPLICATE, "location %s already exists".formatted(d.code()));
        }
        validate(d);
        StorageLocation location = StorageLocation.create(d.code(), d.zoneCode(), d.type(), d.mapCode(), d.accessPoint(),
                d.accessTheta(), d.level(), d.height(), d.allowedCarrierTypes(), clock.instant());
        locations.insert(location);
        return LocationView.of(location);
    }

    /** Creates all locations or none (STO-03). */
    public List<LocationView> createLocations(List<LocationDefinition> definitions) {
        return definitions.stream().map(this::createLocation).toList();
    }

    public LocationView updateLocation(String code, LocationDefinition d) {
        StorageLocation location = requireLocation(code);
        validate(d);
        location.redefine(d.zoneCode(), d.type(), d.mapCode(), d.accessPoint(), d.accessTheta(), d.level(), d.height(),
                d.allowedCarrierTypes(), clock.instant());
        locations.update(location);
        return LocationView.of(location);
    }

    public void deleteLocation(String code) {
        StorageLocation location = requireLocation(code);
        if (location.getCarrierCode() != null || location.getStatus().isLocked()) {
            throw new BizException(StorageErrorCode.LOCATION_STATE_INVALID,
                    "location %s is %s and cannot be deleted".formatted(code, location.getStatus()));
        }
        locations.delete(location);
    }

    @Transactional(readOnly = true)
    public LocationView getLocation(String code) {
        return LocationView.of(requireLocation(code));
    }

    @Transactional(readOnly = true)
    public List<LocationView> listLocations(LocationFilter filter) {
        return locations.find(filter).stream().map(LocationView::of).toList();
    }

    public LocationView disable(String code) {
        StorageLocation location = requireLocation(code);
        location.disable(clock.instant());
        locations.update(location);
        return LocationView.of(location);
    }

    public LocationView enable(String code) {
        StorageLocation location = requireLocation(code);
        location.enable(clock.instant());
        locations.update(location);
        return LocationView.of(location);
    }

    /**
     * Empty locations that accept {@code carrierType} (STO-06), nearest first when {@code nearPoint} on
     * the published map {@code mapCode} is given (straight-line distance between access points), otherwise
     * by code.
     */
    @Transactional(readOnly = true)
    public List<LocationView> findAvailable(String zoneCode, String carrierType, String mapCode, String nearPoint, int limit) {
        List<StorageLocation> candidates = locations.find(new LocationFilter(zoneCode, StorageLocationStatus.EMPTY, mapCode))
                .stream()
                .filter(l -> carrierType == null || l.accepts(carrierType))
                .toList();
        if (nearPoint == null || mapCode == null || !maps.isPublished(mapCode)) {
            return candidates.stream().limit(limit).map(LocationView::of).toList();
        }
        Map<String, PointDto> points = maps.getActiveTopology(mapCode).points().stream()
                .collect(Collectors.toMap(PointDto::code, p -> p));
        PointDto from = points.get(nearPoint);
        if (from == null) {
            throw new BizException(StorageErrorCode.ACCESS_POINT_UNKNOWN,
                    "point %s is not on the published map %s".formatted(nearPoint, mapCode));
        }
        return candidates.stream()
                .filter(l -> points.containsKey(l.getAccessPoint()))
                .map(l -> {
                    PointDto p = points.get(l.getAccessPoint());
                    return LocationView.of(l, Math.hypot(p.x() - from.x(), p.y() - from.y()));
                })
                .sorted(Comparator.comparingDouble(LocationView::distance))
                .limit(limit)
                .toList();
    }

    private void validate(LocationDefinition d) {
        requireZone(d.zoneCode());
        if (maps.isPublished(d.mapCode()) && maps.findActivePoint(d.mapCode(), d.accessPoint()).isEmpty()) {
            throw new BizException(StorageErrorCode.ACCESS_POINT_UNKNOWN,
                    "point %s is not on the published map %s".formatted(d.accessPoint(), d.mapCode()));
        }
    }

    private Zone requireZone(String code) {
        return zones.findByCode(code)
                .orElseThrow(() -> new BizException(StorageErrorCode.ZONE_NOT_FOUND, "zone %s not found".formatted(code)));
    }

    private StorageLocation requireLocation(String code) {
        return locations.findByCode(code).orElseThrow(() ->
                new BizException(StorageErrorCode.LOCATION_NOT_FOUND, "location %s not found".formatted(code)));
    }
}
