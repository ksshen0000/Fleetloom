package org.rcs3000.resource.map.domain;

import java.util.List;
import java.util.Optional;

public interface MapVersionRepository {

    Optional<MapVersion> find(Long mapId, int versionNo);

    Optional<MapVersion> findDraft(Long mapId);

    List<MapVersionSummary> listSummaries(Long mapId);

    /** Highest version number of the map, or 0 if it has none. */
    int maxVersionNo(Long mapId);

    void insert(MapVersion version);

    /**
     * Persists changes to an existing version.
     *
     * @param previousRevision the revision the version had when it was loaded; the update fails with
     *                         {@link MapErrorCode#MAP_REVISION_CONFLICT} if the stored revision differs
     */
    void update(MapVersion version, int previousRevision);

    void delete(MapVersion version);
}
