package org.fleetloom.vda5050.v2;

/** Velocity in the vehicle frame: vx forward (m/s), vy left (m/s), omega counter-clockwise (rad/s). */
public record Velocity(Double vx, Double vy, Double omega) {
}
