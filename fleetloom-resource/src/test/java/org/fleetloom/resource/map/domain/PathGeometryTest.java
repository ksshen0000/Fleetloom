package org.fleetloom.resource.map.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.fleetloom.contracts.map.PathDirection;
import org.fleetloom.contracts.map.PathShape;
import org.fleetloom.contracts.map.VehicleOrientation;

class PathGeometryTest {

    @Test
    void straightLineLengthIsEuclideanDistance() {
        MapPath path = MapFixtures.twoWay("AB", "A", "B");

        assertThat(PathGeometry.length(path, new Position(0, 0), new Position(3, 4))).isEqualTo(5.0);
    }

    @Test
    void bezierWithCollinearControlPointsEqualsStraightDistance() {
        MapPath path = bezier(new Position(1, 0), new Position(2, 0));

        assertThat(PathGeometry.length(path, new Position(0, 0), new Position(3, 0))).isCloseTo(3.0, within(1e-9));
    }

    @Test
    void bezierApproximatingQuarterCircleHasArcLength() {
        // Standard cubic approximation of a unit quarter circle: control distance k = 0.5523
        double k = 4.0 / 3.0 * (Math.sqrt(2) - 1);
        MapPath path = bezier(new Position(1, k), new Position(k, 1));

        double length = PathGeometry.length(path, new Position(1, 0), new Position(0, 1));

        assertThat(length).isCloseTo(Math.PI / 2, within(1e-3));
    }

    @Test
    void bezierEndpointsAreTheCurveEnds() {
        Position p0 = new Position(0, 0);
        Position p3 = new Position(10, 2);

        assertThat(PathGeometry.cubicBezierAt(p0, new Position(3, 5), new Position(7, 5), p3, 0)).isEqualTo(p0);
        assertThat(PathGeometry.cubicBezierAt(p0, new Position(3, 5), new Position(7, 5), p3, 1)).isEqualTo(p3);
    }

    @Test
    void malformedBezierFallsBackToStraightDistance() {
        MapPath path = new MapPath("AB", "A", "B", PathDirection.BIDIRECTIONAL, PathShape.BEZIER,
                List.of(new Position(5, 5)), null, VehicleOrientation.ANY, Set.of(), 1.0, true);

        assertThat(PathGeometry.length(path, new Position(0, 0), new Position(3, 4))).isEqualTo(5.0);
    }

    private static MapPath bezier(Position c1, Position c2) {
        return new MapPath("AB", "A", "B", PathDirection.BIDIRECTIONAL, PathShape.BEZIER, List.of(c1, c2),
                null, VehicleOrientation.ANY, Set.of(), 1.0, true);
    }
}
