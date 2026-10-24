package org.fleetloom.traffic.core;

import java.time.Duration;

/**
 * @param lookaheadNodes        reserve at least this many nodes ahead of the vehicle
 * @param lookaheadDistance     and at least this many meters ahead, so the vehicle need not brake
 * @param turnPenaltyPerRadian  planning cost in seconds per radian of heading change
 * @param defaultSpeed          m/s used for planning and cost estimates
 * @param occupiedPenalty       extra planning seconds for using a resource another vehicle holds
 * @param nodeTolerance         m; a vehicle within this distance of its last node counts as standing on it
 * @param blockedEventAfter     waiting time before a TrafficBlocked event is emitted
 * @param replanAfter           waiting time before a detour around occupied resources is tried
 */
public record TrafficSettings(
        int lookaheadNodes,
        double lookaheadDistance,
        double turnPenaltyPerRadian,
        double defaultSpeed,
        double occupiedPenalty,
        double nodeTolerance,
        Duration blockedEventAfter,
        Duration replanAfter) {

    public static TrafficSettings defaults() {
        return new TrafficSettings(3, 5.0, 2.0, 1.0, 30.0, 0.1, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }
}
