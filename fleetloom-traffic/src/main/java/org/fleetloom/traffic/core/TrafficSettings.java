package org.fleetloom.traffic.core;

import java.time.Duration;

/**
 * @param lookaheadNodes        reserve at least this many nodes ahead of the vehicle
 * @param lookaheadDistance     and at least this many meters ahead, so the vehicle need not brake
 * @param turnPenaltyPerRadian  planning cost in seconds per radian of heading change
 * @param defaultSpeed          m/s used for planning and cost estimates of vehicles whose type is not
 *                              registered; the others plan with their type's top speed
 * @param occupiedPenalty       extra planning seconds for using a resource another vehicle holds
 * @param nodeTolerance         m; a vehicle within this distance of its last node counts as standing on it
 * @param blockedEventAfter     waiting time before a TrafficBlocked event is emitted
 * @param replanAfter           waiting time before a detour around occupied resources is tried
 * @param counterflowPenalty    extra planning seconds, per other vehicle, for driving a path (or a
 *                              single-lane corridor) against the direction that vehicle still has to
 *                              drive it in: such meetings end in waiting or a deadlock
 * @param followPenalty         extra planning seconds, per other vehicle, for driving a path that
 *                              vehicle still has to drive in the same direction; spreads traffic out
 */
public record TrafficSettings(
        int lookaheadNodes,
        double lookaheadDistance,
        double turnPenaltyPerRadian,
        double defaultSpeed,
        double occupiedPenalty,
        double nodeTolerance,
        Duration blockedEventAfter,
        Duration replanAfter,
        double counterflowPenalty,
        double followPenalty) {

    // chosen with TrafficBenchmark: on 4x4 to 6x6 grids with 4 to 12 vehicles they raised arrivals by
    // 11 % over reservations alone and more than halved the deadlocks
    public static final double DEFAULT_COUNTERFLOW_PENALTY = 40.0;
    public static final double DEFAULT_FOLLOW_PENALTY = 4.0;

    public TrafficSettings(int lookaheadNodes, double lookaheadDistance, double turnPenaltyPerRadian, double defaultSpeed,
            double occupiedPenalty, double nodeTolerance, Duration blockedEventAfter, Duration replanAfter) {
        this(lookaheadNodes, lookaheadDistance, turnPenaltyPerRadian, defaultSpeed, occupiedPenalty, nodeTolerance,
                blockedEventAfter, replanAfter, DEFAULT_COUNTERFLOW_PENALTY, DEFAULT_FOLLOW_PENALTY);
    }

    public static TrafficSettings defaults() {
        return new TrafficSettings(3, 5.0, 2.0, 1.0, 30.0, 0.1, Duration.ofSeconds(5), Duration.ofSeconds(10));
    }
}
