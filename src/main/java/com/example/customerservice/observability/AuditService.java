package com.example.customerservice.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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
}
