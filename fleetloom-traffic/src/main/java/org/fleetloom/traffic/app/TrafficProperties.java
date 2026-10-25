package org.fleetloom.traffic.app;

import java.time.Duration;

import org.fleetloom.traffic.core.TrafficSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** See {@link TrafficSettings} for the meaning of the planning and reservation parameters. */
@ConfigurationProperties("fleetloom.traffic")
public record TrafficProperties(
        @DefaultValue("http://localhost:8083") String fleetUrl,
        @DefaultValue("http://localhost:8081") String resourceUrl,
        @DefaultValue("500") long tickMillis,
        @DefaultValue("3") int lookaheadNodes,
        @DefaultValue("5.0") double lookaheadDistance,
        @DefaultValue("2.0") double turnPenaltyPerRadian,
        @DefaultValue("1.0") double defaultSpeed,
        @DefaultValue("30.0") double occupiedPenalty,
        @DefaultValue("0.1") double nodeTolerance,
        @DefaultValue("5s") Duration blockedEventAfter,
        @DefaultValue("10s") Duration replanAfter,
        @DefaultValue("" + TrafficSettings.DEFAULT_COUNTERFLOW_PENALTY) double counterflowPenalty,
        @DefaultValue("" + TrafficSettings.DEFAULT_FOLLOW_PENALTY) double followPenalty) {

    public TrafficSettings toSettings() {
        return new TrafficSettings(lookaheadNodes, lookaheadDistance, turnPenaltyPerRadian, defaultSpeed,
                occupiedPenalty, nodeTolerance, blockedEventAfter, replanAfter, counterflowPenalty, followPenalty);
    }
}
