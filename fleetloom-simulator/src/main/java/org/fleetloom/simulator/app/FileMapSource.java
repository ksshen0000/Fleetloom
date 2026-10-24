package org.fleetloom.simulator.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fleetloom.simulator.core.SiteMap;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads the map from a JSON file:
 *
 * <pre>{@code
 * {"mapId": "HALL1", "version": "3",
 *  "points": [{"code": "P1", "x": 0, "y": 0, "type": "CHARGING"}, ...],
 *  "paths":  [{"code": "P1-P2", "fromPoint": "P1", "toPoint": "P2",
 *              "controlPoints": [{"x": 1, "y": 0}, {"x": 2, "y": 1}]}, ...]}
 * }</pre>
 *
 * {@code mapId} and {@code version} are optional, {@code type} and {@code controlPoints} (two for a
 * cubic Bezier path) too; anything else is ignored. A map exported from Fleetloom (or the import
 * files under {@code deploy/examples}) can be used as it is.
 */
public final class FileMapSource implements MapSource {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Path file;
    private final String mapId;

    /** @param mapId used when the file names none; the file name without extension when empty */
    public FileMapSource(Path file, String mapId) {
        this.file = file;
        String name = file.getFileName().toString();
        this.mapId = mapId == null || mapId.isBlank() ? name.replaceFirst("\\.[^.]*$", "") : mapId;
    }

    @Override
    public SiteMap load() {
        MapFile content;
        try {
            content = JSON.readValue(Files.readString(file), MapFile.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot read map file " + file + ": " + e.getMessage(), e);
        }
        Map<String, SiteMap.Point> points = new HashMap<>();
        for (PointEntry p : content.points() == null ? List.<PointEntry>of() : content.points()) {
            points.put(p.code(), new SiteMap.Point(p.x(), p.y(), p.type()));
        }
        Map<String, SiteMap.Path> paths = new HashMap<>();
        for (PathEntry p : content.paths() == null ? List.<PathEntry>of() : content.paths()) {
            if (!points.containsKey(p.fromPoint()) || !points.containsKey(p.toPoint())) {
                throw new IllegalStateException("path %s in %s joins unknown points".formatted(p.code(), file));
            }
            List<double[]> controls = p.controlPoints() == null ? List.of()
                    : p.controlPoints().stream().map(c -> new double[] {c.x(), c.y()}).toList();
            paths.put(p.code(), new SiteMap.Path(p.fromPoint(), p.toPoint(), controls));
        }
        return new SiteMap(content.mapId() == null ? mapId : content.mapId(),
                content.version() == null ? "1" : content.version(), points, paths);
    }

    @Override
    public String describe() {
        return "file " + file;
    }

    record MapFile(String mapId, String version, List<PointEntry> points, List<PathEntry> paths) {
    }

    record PointEntry(String code, double x, double y, String type) {
    }

    record PathEntry(String code, String fromPoint, String toPoint, List<Position> controlPoints) {
    }

    record Position(double x, double y) {
    }
}
