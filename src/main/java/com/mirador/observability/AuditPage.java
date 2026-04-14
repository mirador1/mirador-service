package com.mirador.observability;

import java.util.List;

/**
 * Paginated response wrapper for {@code GET /audit}.
 */
public record AuditPage(
        List<AuditEventDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public AuditPage {
        content = List.copyOf(content);
    }
}
