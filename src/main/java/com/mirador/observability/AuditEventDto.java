package com.mirador.observability;

import java.time.Instant;

/**
 * Read-only DTO for audit events returned by {@code GET /audit}.
 */
public record AuditEventDto(
        Long id,
        String userName,
        String action,
        String detail,
        String ipAddress,
        Instant createdAt) {
}
