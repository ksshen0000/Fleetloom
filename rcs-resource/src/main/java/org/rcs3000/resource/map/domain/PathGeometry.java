package org.rcs3000.resource.map.domain;

import org.rcs3000.contracts.map.PathShape;

/** Geometric calculations on paths. */
public final class PathGeometry {

    /** Segments used to approximate a Bezier curve; error is well below 1 mm for warehouse-scale curves. */
    static final int BEZIER_SEGMENTS = 64;

    private PathGeometry() {
    }

    /**
     * Length of a path in meters. A Bezier path without exactly two control points is measured as a
     * straight line (the validator reports it separately).
     */
    public static double length(MapPath path, Position from, Position to) {
        if (path.shape() == PathShape.BEZIER && path.controlPoints().size() == 2) {
            return cubicBezierLength(from, path.controlPoints().get(0), path.controlPoints().get(1), to);
        }
        return from.distanceTo(to);
    }

    static double cubicBezierLength(Position p0, Position p1, Position p2, Position p3) {
        double length = 0;
        Position previous = p0;
        for (int i = 1; i <= BEZIER_SEGMENTS; i++) {
            Position current = cubicBezierAt(p0, p1, p2, p3, (double) i / BEZIER_SEGMENTS);
            length += previous.distanceTo(current);
            previous = current;
        }
        return length;
    }

    static Position cubicBezierAt(Position p0, Position p1, Position p2, Position p3, double t) {
        double u = 1 - t;
        double a = u * u * u;
        double b = 3 * u * u * t;
        double c = 3 * u * t * t;
        double d = t * t * t;
        return new Position(
                a * p0.x() + b * p1.x() + c * p2.x() + d * p3.x(),
                a * p0.y() + b * p1.y() + c * p2.y() + d * p3.y());
    }
}
