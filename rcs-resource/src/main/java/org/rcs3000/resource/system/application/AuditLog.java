package org.rcs3000.resource.system.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.rcs3000.common.event.EventBus;
import org.rcs3000.contracts.audit.AuditRecordedEvent;
import org.rcs3000.resource.system.domain.AuditEntry;
import org.rcs3000.resource.system.domain.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The operation audit log (WEB-08): what the gateway reports is stored here and kept for
 * {@code rcs.audit.retention} (default 180 days).
 */
@Service
public class AuditLog implements ApplicationRunner {

    static final String GROUP = "rcs-resource-audit";
    static final int MAX_LIMIT = 2000;
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final AuditRepository entries;
    private final EventBus events;
    private final Clock clock;
    private final Duration retention;

    public AuditLog(AuditRepository entries, EventBus events, Clock clock,
            @Value("${rcs.audit.retention:180d}") Duration retention) {
        this.entries = entries;
        this.events = events;
        this.clock = clock;
        this.retention = retention;
    }

    @Override
    public void run(ApplicationArguments args) {
        // durable: what happened while this service was down is recorded once it is back
        events.subscribe(AuditRecordedEvent.TYPE, GROUP, AuditRecordedEvent.class, e -> record(e.payload()));
    }

    public void record(AuditRecordedEvent e) {
        entries.insert(new AuditEntry(null, e.at(), e.username(), e.role(), e.method(), e.path(), e.query(), e.status(),
                e.durationMs(), e.clientIp()));
    }

    public List<AuditEntry> find(String username, String pathPrefix, boolean failedOnly, Instant from, Instant to, int limit) {
        return entries.find(blankToNull(username), blankToNull(pathPrefix), failedOnly, from, to, Math.clamp(limit, 1, MAX_LIMIT));
    }

    @Scheduled(cron = "${rcs.audit.purge-cron:0 30 3 * * *}")
    public void purge() {
        int removed = entries.deleteBefore(clock.instant().minus(retention));
        if (removed > 0) {
            log.info("removed {} audit entries older than {}", removed, retention);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
