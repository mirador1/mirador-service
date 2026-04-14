package com.mirador.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists security audit events to the {@code audit_event} table.
 *
 * <p>All writes are async ({@code @Async}) to avoid adding latency to the request path.
 * Events are also logged to stdout for Loki/ELK ingestion.
 *
 * <p>Audit events include:
 * <ul>
 *   <li>LOGIN_SUCCESS / LOGIN_FAILED / LOGIN_BLOCKED</li>
 *   <li>CUSTOMER_CREATED / CUSTOMER_UPDATED / CUSTOMER_DELETED</li>
 *   <li>TOKEN_REFRESH</li>
 *   <li>API_KEY_AUTH</li>
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Async
    public void log(String userName, String action, String detail, String ipAddress) {
        log.info("audit_event user={} action={} detail={} ip={}", userName, action, detail, ipAddress);
        try {
            jdbc.update("INSERT INTO audit_event (user_name, action, detail, ip_address) VALUES (?, ?, ?, ?)",
                    userName, action, detail, ipAddress);
        } catch (Exception e) {
            log.error("audit_event_persist_failed action={} error={}", action, e.getMessage());
        }
    }

    /**
     * Returns a page of audit events with optional filters on action, user, and time range.
     *
     * @param page  zero-based page index
     * @param size  page size (number of rows)
     * @param action optional action filter (exact match)
     * @param user   optional user_name filter (exact match)
     * @param from   optional lower bound for created_at (inclusive)
     * @param to     optional upper bound for created_at (inclusive)
     */
    public AuditPage findAll(int page, int size, String action, String user, Instant from, Instant to) {
        // Build WHERE clause dynamically
        var whereClauses = new ArrayList<String>();
        var params = new ArrayList<Object>();

        if (action != null && !action.isBlank()) {
            whereClauses.add("action = ?");
            params.add(action);
        }
        if (user != null && !user.isBlank()) {
            whereClauses.add("user_name = ?");
            params.add(user);
        }
        if (from != null) {
            whereClauses.add("created_at >= ?");
            params.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            whereClauses.add("created_at <= ?");
            params.add(java.sql.Timestamp.from(to));
        }

        String where = whereClauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", whereClauses);

        // Count total matching rows
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event" + where,
                Long.class, params.toArray());
        long totalElements = total != null ? total : 0L;
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;

        // Fetch the page
        var pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add((long) page * size);

        List<AuditEventDto> content = jdbc.query(
                "SELECT id, user_name, action, detail, ip_address, created_at" +
                        " FROM audit_event" + where +
                        " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapRow(rs),
                pageParams.toArray());

        return new AuditPage(content, page, size, totalElements, totalPages);
    }

    /**
     * Returns all audit events related to a specific customer ID.
     * Searches the {@code detail} column for entries containing {@code "id=<customerId>"}.
     *
     * @param customerId the customer whose audit trail is requested
     * @return list of matching events ordered by creation time descending (newest first)
     */
    public List<AuditEventDto> findByCustomerId(Long customerId) {
        // Pattern matches "id=42 name=..." and "id=42" at end of detail string
        String pattern = "id=" + customerId + " %";
        return jdbc.query(
                "SELECT id, user_name, action, detail, ip_address, created_at " +
                "FROM audit_event WHERE detail LIKE ? ORDER BY created_at DESC LIMIT 100",
                (rs, rowNum) -> mapRow(rs),
                pattern);
    }

    private AuditEventDto mapRow(ResultSet rs) throws SQLException {
        return new AuditEventDto(
                rs.getLong("id"),
                rs.getString("user_name"),
                rs.getString("action"),
                rs.getString("detail"),
                rs.getString("ip_address"),
                rs.getTimestamp("created_at").toInstant());
    }
}
