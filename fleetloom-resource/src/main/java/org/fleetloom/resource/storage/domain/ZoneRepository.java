package org.fleetloom.resource.storage.domain;

import java.util.List;
import java.util.Optional;

public interface ZoneRepository {

    Optional<Zone> findByCode(String code);

    List<Zone> findAll();

    /** Inserts when {@code id} is null, otherwise updates; returns the stored zone. */
    Zone save(Zone zone);

    void delete(Zone zone);
}
