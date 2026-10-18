package org.rcs3000.resource.map.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.rcs3000.contracts.map.PathDirection;
import org.rcs3000.contracts.map.PathShape;
import org.rcs3000.contracts.map.PointType;
import org.rcs3000.contracts.map.VehicleOrientation;

/** Builders for map content used across tests. */
public final class MapFixtures {

    private MapFixtures() {
    }

    public static MapPoint point(String code, double x, double y) {
        return point(code, x, y, PointType.NORMAL);
    }

    public static MapPoint point(String code, double x, double y, PointType type) {
        return new MapPoint(code, x, y, null, type, Map.of());
    }

    public static MapPath line(String code, String from, String to, PathDirection direction) {
        return new MapPath(code, from, to, direction, PathShape.LINE, List.of(), null, VehicleOrientation.ANY,
                Set.of(), 1.0, true);
    }

    public static MapPath twoWay(String code, String from, String to) {
        return line(code, from, to, PathDirection.BIDIRECTIONAL);
    }

    public static MapPath oneWay(String code, String from, String to) {
        return line(code, from, to, PathDirection.UNIDIRECTIONAL);
    }

    /**
     * A valid square loop A-B-C-D with a charging point E hanging off A by a two-way path.
     *
     * <pre>
     * D(0,10) --- C(10,10)
     *   |            |
     * A(0,0)  --- B(10,0) ;  E(-5,0) charging, two-way to A
     * </pre>
     */
    public static MapContent validSquare() {
        return new MapContent(
                List.of(point("A", 0, 0), point("B", 10, 0), point("C", 10, 10), point("D", 0, 10),
                        point("E", -5, 0, PointType.CHARGING)),
                List.of(twoWay("AB", "A", "B"), twoWay("BC", "B", "C"), twoWay("CD", "C", "D"), twoWay("DA", "D", "A"),
                        twoWay("AE", "A", "E")),
                List.of());
    }
}
