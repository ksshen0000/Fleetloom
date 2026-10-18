package org.rcs3000.resource.map.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editable body of a map version: points, paths and areas. Immutable value.
 *
 * <p>Persisted as one JSON document per version, so renaming a component here requires a data
 * migration of stored versions.
 */
public record MapContent(List<MapPoint> points, List<MapPath> paths, List<MapArea> areas) {

    public MapContent {
        points = points == null ? List.of() : List.copyOf(points);
        paths = paths == null ? List.of() : List.copyOf(paths);
        areas = areas == null ? List.of() : List.copyOf(areas);
    }

    public static MapContent empty() {
        return new MapContent(List.of(), List.of(), List.of());
    }

    /** Points keyed by code; on duplicate codes the first occurrence wins. */
    public Map<String, MapPoint> pointsByCode() {
        Map<String, MapPoint> byCode = new LinkedHashMap<>();
        for (MapPoint point : points) {
            byCode.putIfAbsent(point.code(), point);
        }
        return byCode;
    }
}
