package org.fleetloom.resource.storage.domain;

/** Optional criteria for listing storage locations; {@code null} fields do not filter. */
public record LocationFilter(String zoneCode, StorageLocationStatus status, String mapCode) {

    public static final LocationFilter ALL = new LocationFilter(null, null, null);
}
