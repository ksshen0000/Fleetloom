package org.rcs3000.resource.map.domain;

import java.util.List;
import java.util.Optional;

public interface MapRepository {

    Optional<RcsMap> findByCode(String code);

    boolean existsByCode(String code);

    List<RcsMap> findAll();

    /** Inserts a new map (assigning its id) or updates an existing one. */
    void save(RcsMap map);

    void delete(RcsMap map);
}
