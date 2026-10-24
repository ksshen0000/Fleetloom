package org.fleetloom.dispatch.app;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Order status callbacks to the upstream system (DSP-11).
 *
 * @param url            where to POST every order status change; empty disables callbacks
 * @param secret         if set, each request carries {@code X-Fleetloom-Signature: sha256=<hex HMAC of the body>}
 * @param retries        further attempts after a failed one
 * @param initialBackoff wait before the first retry; doubled for each further one
 */
@ConfigurationProperties("fleetloom.dispatch.callback")
public record CallbackProperties(
        @DefaultValue("") String url,
        @DefaultValue("") String secret,
        @DefaultValue("3") int retries,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("5s") Duration timeout) {

    public boolean enabled() {
        return url != null && !url.isBlank();
    }
}
