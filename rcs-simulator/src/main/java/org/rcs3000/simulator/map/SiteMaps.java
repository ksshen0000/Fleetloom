package org.rcs3000.simulator.map;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.rcs3000.contracts.map.MapTopologyDto;
import org.rcs3000.contracts.map.PathDto;
import org.rcs3000.contracts.map.PointDto;
import org.rcs3000.simulator.core.SiteMap;

/** Converts the map service's topology into the simulator's {@link SiteMap}. */
public final class SiteMaps {

    private SiteMaps() {
    }

    public static SiteMap from(MapTopologyDto topology) {
        Map<String, SiteMap.Point> points = new HashMap<>();
        for (PointDto p : topology.points()) {
            points.put(p.code(), new SiteMap.Point(p.x(), p.y(), p.type() == null ? null : p.type().name()));
        }
        Map<String, SiteMap.Path> paths = new HashMap<>();
        for (PathDto p : topology.paths()) {
            List<double[]> controls = p.controlPoints() == null ? List.of()
                    : p.controlPoints().stream().map(c -> new double[] {c.x(), c.y()}).toList();
            paths.put(p.code(), new SiteMap.Path(p.fromPoint(), p.toPoint(), controls));
        }
        return new SiteMap(topology.mapCode(), Integer.toString(topology.versionNo()), points, paths);
    }
}
