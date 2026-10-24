package org.fleetloom.resource.map.domain;

import java.util.List;
import java.util.Optional;

public interface MapRepository {

    Optional<SiteMapDefinition> findByCode(String code);

    boolean existsByCode(String code);

    List<SiteMapDefinition> findAll();

    /** Inserts a new map (assigning its id) or updates an existing one. */
    void save(SiteMapDefinition map);

    void delete(SiteMapDefinition map);
}
