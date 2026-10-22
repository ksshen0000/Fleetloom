package org.rcs3000.contracts.audit;

import java.time.Instant;

/**
 * One changing request through the gateway (WEB-08 operation audit), whether it succeeded or was refused.
 *
 * @param username   the authenticated user; {@code null} if the request carried no valid token
 * @param status     HTTP status of the response; 0 if the client went away before it was sent
 */
public record AuditRecordedEvent(Instant at, String username, String role, String method, String path, String query,
        int status, long durationMs, String clientIp) {

    public static final String TYPE = "AuditRecorded";
}
