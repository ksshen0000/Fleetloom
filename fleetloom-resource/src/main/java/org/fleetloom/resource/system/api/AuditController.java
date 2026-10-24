package org.fleetloom.resource.system.api;

import java.time.Instant;
import java.util.List;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.resource.system.application.AuditLog;
import org.fleetloom.resource.system.domain.AuditEntry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operation audit log (WEB-08); the gateway restricts it to administrators. */
@RestController
@RequestMapping("/api/resource/v1/audit-logs")
public class AuditController {

    private final AuditLog audit;

    public AuditController(AuditLog audit) {
        this.audit = audit;
    }

    /** @param path path prefix, e.g. {@code /api/dispatch} */
    @GetMapping
    public ApiResponse<List<AuditEntry>> find(@RequestParam(required = false) String user,
            @RequestParam(required = false) String path, @RequestParam(defaultValue = "false") boolean failedOnly,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(audit.find(user, path, failedOnly, from, to, limit));
    }
}
