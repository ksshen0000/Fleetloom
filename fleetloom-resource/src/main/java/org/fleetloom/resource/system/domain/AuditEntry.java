package org.fleetloom.resource.system.domain;

import java.time.Instant;

/** A changing request someone made through the gateway (WEB-08); see {@code AuditRecordedEvent}. */
public record AuditEntry(Long id, Instant at, String username, String role, String method, String path, String query,
        int status, long durationMs, String clientIp) {
}
