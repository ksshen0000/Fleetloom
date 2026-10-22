package org.rcs3000.resource.system.domain;

import java.time.Instant;

/** Outbound port: signs access tokens that the gateway verifies. */
public interface TokenIssuer {

    IssuedToken issue(User user, Instant now);

    record IssuedToken(String token, Instant expiresAt) {
    }
}
