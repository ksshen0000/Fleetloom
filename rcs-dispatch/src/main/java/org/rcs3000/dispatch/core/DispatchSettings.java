package org.rcs3000.dispatch.core;

import java.time.Duration;

/**
 * @param routePollInterval how often running routes are checked with the traffic service in case an
 *                          event was missed
 * @param registryRefresh   how often the vehicle registry is reloaded from rcs-resource
 * @param parkAfterIdle     an idle vehicle off a parking point goes to the nearest free one after this
 *                          long (DSP-08); zero turns parking off
 */
public record DispatchSettings(Duration routePollInterval, Duration registryRefresh, int defaultPriority,
        Duration parkAfterIdle) {

    public static DispatchSettings defaults() {
        return new DispatchSettings(Duration.ofSeconds(5), Duration.ofSeconds(30), 5, Duration.ofSeconds(30));
    }
}
