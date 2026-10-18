package org.rcs3000.fleet.app;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param heartbeatTimeout an online vehicle that sends no state for this long is marked CONNECTION_BROKEN
 */
@ConfigurationProperties("rcs.fleet")
public record FleetProperties(@DefaultValue Mqtt mqtt, @DefaultValue("5s") Duration heartbeatTimeout) {

    public record Mqtt(
            @DefaultValue("localhost") String host,
            @DefaultValue("1883") int port,
            @DefaultValue("uagv") String interfaceName,
            @DefaultValue("rcs-fleet") String clientId) {
    }
}
