package org.rcs3000.resource.storage.domain;

import java.util.List;
import java.util.Optional;

public interface StorageLocationRepository {

    Optional<StorageLocation> findByCode(String code);

    List<StorageLocation> find(LocationFilter filter);

    boolean existsInZone(String zoneCode);

    void insert(StorageLocation location);

    /**
     * Stores changes if nobody else changed the location since it was loaded; otherwise fails with
     * {@link StorageErrorCode#STORAGE_CONCURRENT_MODIFICATION}.
     */
    void update(StorageLocation location);

    void delete(StorageLocation location);
}
