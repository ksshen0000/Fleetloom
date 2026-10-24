package org.fleetloom.resource.map.domain;

/** A 2D position in meters, map frame (origin bottom-left, x right, y up). */
public record Position(double x, double y) {

    public double distanceTo(Position other) {
        return Math.hypot(other.x - x, other.y - y);
    }
}
