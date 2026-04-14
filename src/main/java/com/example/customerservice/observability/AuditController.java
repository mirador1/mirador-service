package com.example.customerservice.observability;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * REST controller exposing the audit event log at {@code GET /audit}.
 *
 * <p>Requires authentication (any authenticated user). Supports optional filtering
 * by action type, user name, and time range.
 */
@RestController
@RequestMapping("/audit")
@PreAuthorize("isAuthenticated()")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * Returns a paginated list of audit events.
     *
     * @param page   zero-based page index (default 0)
     * @param size   page size (default 20)
     * @param action optional action filter (e.g. LOGIN_FAILED)
     * @param user   optional user_name filter
     * @param from   optional ISO-8601 start timestamp (inclusive)
     * @param to     optional ISO-8601 end timestamp (inclusive)
     */
    @GetMapping
    public AuditPage getAuditEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        int cappedSize = Math.min(size, 100);
        return auditService.findAll(page, cappedSize, action, user, from, to);
    }
}
