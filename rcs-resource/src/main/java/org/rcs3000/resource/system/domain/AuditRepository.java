package org.rcs3000.resource.system.domain;

import java.time.Instant;
import java.util.List;

public interface AuditRepository {

    void insert(AuditEntry entry);

    /**
     * Newest first. Every filter is optional.
     *
     * @param pathPrefix e.g. {@code /api/dispatch} for everything done to orders
     * @param failedOnly only requests answered with status 400 or above (or never answered)
     */
    List<AuditEntry> find(String username, String pathPrefix, boolean failedOnly, Instant from, Instant to, int limit);

    /** @return how many entries were removed */
    int deleteBefore(Instant cutoff);
}
