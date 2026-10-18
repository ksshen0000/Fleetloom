package org.rcs3000.resource.map.domain;

import java.util.Map;

import org.rcs3000.contracts.map.PointType;

/**
 * @param theta heading in radians, or {@code null} when the point imposes no heading
 */
public record MapPoint(String code, double x, double y, Double theta, PointType type, Map<String, String> properties) {

    public MapPoint {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    public Position position() {
        return new Position(x, y);
    }
}
