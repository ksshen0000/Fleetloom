package org.fleetloom.traffic.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.fleetloom.contracts.map.AreaDto;
import org.fleetloom.contracts.map.AreaType;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.fleetloom.contracts.map.PathDirection;
import org.fleetloom.contracts.map.PathDto;
import org.fleetloom.contracts.map.PathShape;
import org.fleetloom.contracts.map.PointDto;
import org.fleetloom.contracts.map.PointType;
import org.fleetloom.contracts.map.PositionDto;
import org.fleetloom.contracts.map.VehicleOrientation;

/** Map topologies for tests. Grid points are named {@code P<row><col>}, row 0 at y = 0. */
public final class TestMaps {

    public static final String MAP = "TEST";

    private TestMaps() {
    }

    /** A rows × cols grid with bidirectional paths between neighbours, named {@code P00-P01}. */
    public static MapTopologyDto grid(int rows, int cols, double spacing) {
        return grid(rows, cols, spacing, List.of());
    }

    public static MapTopologyDto grid(int rows, int cols, double spacing, List<AreaDto> areas) {
        List<PointDto> points = new ArrayList<>();
        List<PathDto> paths = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                points.add(point(name(r, c), c * spacing, r * spacing));
                if (c + 1 < cols) {
                    paths.add(path(name(r, c) + "-" + name(r, c + 1), name(r, c), name(r, c + 1), PathDirection.BIDIRECTIONAL, spacing));
                }
                if (r + 1 < rows) {
                    paths.add(path(name(r, c) + "-" + name(r + 1, c), name(r, c), name(r + 1, c), PathDirection.BIDIRECTIONAL, spacing));
                }
            }
        }
        return new MapTopologyDto(MAP, 1, points, paths, areas);
    }

    /** A straight corridor A - B - C, 4 m apart, without any way around. */
    public static MapTopologyDto corridor() {
        return new MapTopologyDto(MAP, 1,
                List.of(point("A", 0, 0), point("B", 4, 0), point("C", 8, 0)),
                List.of(path("AB", "A", "B", PathDirection.BIDIRECTIONAL, 4), path("BC", "B", "C", PathDirection.BIDIRECTIONAL, 4)),
                List.of());
    }

    /**
     * A single-lane corridor A - B - C - D between two junctions, each with two spurs: W1 and W2 at
     * the west end (A), E1 and E2 at the east end (D). The corridor runs along y = 0 from x = 0 to 12.
     */
    public static MapTopologyDto corridorBetweenJunctions() {
        PathDirection both = PathDirection.BIDIRECTIONAL;
        double spur = Math.hypot(4, 4);
        return new MapTopologyDto(MAP, 1,
                List.of(point("W1", -4, 4), point("W2", -4, -4), point("A", 0, 0), point("B", 4, 0), point("C", 8, 0),
                        point("D", 12, 0), point("E1", 16, 4), point("E2", 16, -4)),
                List.of(path("W1A", "W1", "A", both, spur), path("W2A", "W2", "A", both, spur),
                        path("AB", "A", "B", both, 4), path("BC", "B", "C", both, 4), path("CD", "C", "D", both, 4),
                        path("DE1", "D", "E1", both, spur), path("DE2", "D", "E2", both, spur)),
                List.of());
    }

    /**
     * A road A - B - C - D with a side branch S off B (4 m north of it): where two vehicles meet
     * head-on between B and D, one can back off into S.
     */
    public static MapTopologyDto roadWithSideBranch() {
        PathDirection both = PathDirection.BIDIRECTIONAL;
        return new MapTopologyDto(MAP, 1,
                List.of(point("A", 0, 0), point("B", 4, 0), point("C", 8, 0), point("D", 12, 0), point("S", 4, 4)),
                List.of(path("AB", "A", "B", both, 4), path("BC", "B", "C", both, 4), path("CD", "C", "D", both, 4),
                        path("BS", "B", "S", both, 4)),
                List.of());
    }

    public static String name(int row, int col) {
        return "P" + row + col;
    }

    public static PointDto point(String code, double x, double y) {
        return new PointDto(code, x, y, null, PointType.NORMAL, Map.of());
    }

    public static PathDto path(String code, String from, String to, PathDirection direction, double length) {
        return new PathDto(code, from, to, direction, PathShape.LINE, List.of(), null, VehicleOrientation.ANY, null,
                1.0, true, length);
    }

    public static AreaDto rectangle(String code, AreaType type, double x0, double y0, double x1, double y1) {
        return new AreaDto(code, type, List.of(new PositionDto(x0, y0), new PositionDto(x1, y0), new PositionDto(x1, y1),
                new PositionDto(x0, y1)), Map.of());
    }
}
