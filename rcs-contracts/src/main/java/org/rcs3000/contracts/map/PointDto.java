package org.rcs3000.contracts.map;

import java.util.Map;

/**
 * @param theta heading in radians, or {@code null} when the point imposes no heading
 */
public record PointDto(
        String code,
        double x,
        double y,
        Double theta,
        PointType type,
        Map<String, String> properties) {
}
