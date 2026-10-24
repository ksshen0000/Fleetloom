package org.fleetloom.resource.map.domain;

import java.util.List;
import java.util.Map;

import org.fleetloom.contracts.map.AreaType;

public record MapArea(String code, AreaType type, List<Position> polygon, Map<String, String> properties) {

    public MapArea {
        polygon = polygon == null ? List.of() : List.copyOf(polygon);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
