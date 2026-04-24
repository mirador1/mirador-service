package com.mirador.observability;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Audit Trail", description = "Paginated security and data-mutation events — login attempts, CRUD operations, token refreshes")
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
    @Operation(summary = "List audit events",
            description = "Returns a paginated list of security and mutation events. "
                    + "Filter by action (e.g. `LOGIN_FAILED`, `CUSTOMER_CREATED`), username, or time range. "
                    + "Page size is capped at 100.")
    @GetMapping
    public AuditPage getAuditEvents(
            @Parameter(description = "Zero-based page index", example = "0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size (max 100)", example = "20") @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Filter by action type, e.g. LOGIN_FAILED, CUSTOMER_CREATED", example = "LOGIN_FAILED") @RequestParam(required = false) String action,
            @Parameter(description = "Filter by username") @RequestParam(required = false) String user,
            @Parameter(description = "Start of time range (ISO-8601)", example = "2024-01-01T00:00:00Z")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "End of time range (ISO-8601)", example = "2024-12-31T23:59:59Z")
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        int cappedSize = Math.min(size, 100);
        return auditService.findAll(page, cappedSize, action, user, from, to);
    }
}
