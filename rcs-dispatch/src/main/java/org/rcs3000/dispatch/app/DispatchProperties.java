package org.rcs3000.dispatch.app;

import java.time.Duration;

import org.rcs3000.dispatch.core.DispatchSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("rcs.dispatch")
public record DispatchProperties(
        @DefaultValue("http://localhost:8081") String resourceUrl,
        @DefaultValue("http://localhost:8084") String trafficUrl,
        @DefaultValue("http://localhost:8083") String fleetUrl,
        @DefaultValue("1000") long tickMillis,
        @DefaultValue("5s") Duration routePollInterval,
        @DefaultValue("30s") Duration registryRefresh,
        @DefaultValue("5") int defaultPriority,
        @DefaultValue("30s") Duration parkAfterIdle) {

    public DispatchSettings toSettings() {
        return new DispatchSettings(routePollInterval, registryRefresh, defaultPriority, parkAfterIdle);
    }
}
