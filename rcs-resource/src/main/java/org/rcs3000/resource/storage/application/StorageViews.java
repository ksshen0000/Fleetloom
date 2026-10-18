package org.rcs3000.resource.storage.application;

import java.time.Instant;
import java.util.Set;

import org.rcs3000.resource.storage.domain.StorageLocation;
import org.rcs3000.resource.storage.domain.StorageLocationStatus;
import org.rcs3000.resource.storage.domain.StorageLocationType;
import org.rcs3000.resource.storage.domain.Zone;

/** Read models returned by the storage use cases. */
public final class StorageViews {

    private StorageViews() {
    }

    public record ZoneView(String code, String name, String type, String description, Instant createdAt, Instant updatedAt) {

        static ZoneView of(Zone z) {
            return new ZoneView(z.code(), z.name(), z.type(), z.description(), z.createdAt(), z.updatedAt());
        }
    }

    /** @param distance meters from the point asked about, when the query named one */
    public record LocationView(String code, String zoneCode, StorageLocationType type, String mapCode, String accessPoint,
            Double accessTheta, int level, double height, Set<String> allowedCarrierTypes, StorageLocationStatus status,
            String lockOwner, String carrierCode, Instant updatedAt, Double distance) {

        public static LocationView of(StorageLocation l) {
            return of(l, null);
        }

        static LocationView of(StorageLocation l, Double distance) {
            return new LocationView(l.getCode(), l.getZoneCode(), l.getType(), l.getMapCode(), l.getAccessPoint(),
                    l.getAccessTheta(), l.getLevel(), l.getHeight(), l.getAllowedCarrierTypes(), l.getStatus(),
                    l.getLockOwner(), l.getCarrierCode(), l.getUpdatedAt(), distance);
        }
    }

    /** What a storage location is: everything except its state. */
    public record LocationDefinition(String code, String zoneCode, StorageLocationType type, String mapCode,
            String accessPoint, Double accessTheta, int level, double height, Set<String> allowedCarrierTypes) {

        public LocationDefinition {
            allowedCarrierTypes = allowedCarrierTypes == null ? Set.of() : Set.copyOf(allowedCarrierTypes);
        }
    }
}
