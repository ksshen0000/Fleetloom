package org.rcs3000.fleet.app;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param heartbeatTimeout an online vehicle that sends no state for this long is marked CONNECTION_BROKEN
 * @param messageLog what the vehicle message log keeps (FLT-07)
 */
@ConfigurationProperties("rcs.fleet")
public record FleetProperties(@DefaultValue Mqtt mqtt, @DefaultValue("5s") Duration heartbeatTimeout,
        @DefaultValue MessageLog messageLog) {

    /**
     * @param retention how long messages are kept
     * @param stateInterval an unchanged state is still logged this often, as a sign of life
     */
    public record MessageLog(@DefaultValue("3d") Duration retention, @DefaultValue("60s") Duration stateInterval) {
    }

    public record Mqtt(
            @DefaultValue("localhost") String host,
            @DefaultValue("1883") int port,
            @DefaultValue("uagv") String interfaceName,
            @DefaultValue("rcs-fleet") String clientId) {
    }
}
