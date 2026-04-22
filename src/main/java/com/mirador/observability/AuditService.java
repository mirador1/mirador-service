package com.mirador.observability;

import com.mirador.observability.port.AuditEventPort;
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
 *
 * <p><b>Port:</b> this class implements {@link AuditEventPort} for cross-feature
 * write callers ({@code CustomerService}, {@code AuthController}). Intra-feature
 * read callers ({@code AuditController}) keep depending on this concrete class
 * until a read-side port is justified. Extracted 2026-04-22 as proposal #3 of
 * the Clean Code audit.
 */
@Service
public class AuditService implements AuditEventPort {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Asynchronously persists an audit event and logs it at INFO level.
     *
     * <p>The {@code @Async} annotation runs this on the shared Spring async thread pool
     * so the calling request thread is not delayed by the INSERT. If the INSERT fails
     * (DB unavailable, schema mismatch), the failure is logged at ERROR but silently
     * swallowed — audit logging must never break the normal request path.
     *
     * @apiNote {@code ipAddress} may be {@code null} when the event originates from a
     *          background task or internal method rather than an HTTP request.
     * @implNote The INSERT uses {@code JdbcTemplate} (not JPA) to avoid triggering
     *           Hibernate session management overhead on every audit write.
     */
    @Async
    @Override
    public void recordEvent(String userName, String action, String detail, String ipAddress) {
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
    // Only these exact clause strings can ever reach SQL concatenation, so
    // the dynamic WHERE cannot carry user input into the query shape. The
    // actual filter values are always passed as JDBC parameters (?). The
    // @SuppressWarnings below documents this closed set for Sonar S2077,
    // which can't statically prove the safety itself.
    private static final String CLAUSE_ACTION = "action = ?";
    private static final String CLAUSE_USER   = "user_name = ?";
    private static final String CLAUSE_FROM   = "created_at >= ?";
    private static final String CLAUSE_TO     = "created_at <= ?";

    @SuppressWarnings("java:S2077") // WHERE built from a closed enum of constants; values bind via ? placeholders
    public AuditPage findAll(int page, int size, String action, String user, Instant from, Instant to) {
        // Build WHERE clause from a fixed set of constant fragments. No
        // branch of this chain ever concatenates the user input itself —
        // only hard-coded column references.
        var whereClauses = new ArrayList<String>();
        var params = new ArrayList<Object>();

        if (action != null && !action.isBlank()) {
            whereClauses.add(CLAUSE_ACTION);
            params.add(action);
        }
        if (user != null && !user.isBlank()) {
            whereClauses.add(CLAUSE_USER);
            params.add(user);
        }
        if (from != null) {
            whereClauses.add(CLAUSE_FROM);
            params.add(java.sql.Timestamp.from(from));
        }
        if (to != null) {
            whereClauses.add(CLAUSE_TO);
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
