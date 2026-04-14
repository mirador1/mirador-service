package com.mirador.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom actuator endpoint at /actuator/maintenance.
 *
 * Exposes database maintenance operations (VACUUM, VACUUM FULL) that cannot be
 * run through pgweb (read-only mode). Writes are protected by the same actuator
 * security as other write endpoints.
 *
 * Usage:
 *   VACUUM ANALYZE:  POST /actuator/maintenance  {"operation":"vacuum"}
 *   VACUUM FULL:     POST /actuator/maintenance  {"operation":"vacuumFull"}
 *   VACUUM VERBOSE:  POST /actuator/maintenance  {"operation":"vacuumVerbose"}
 */
@Component
@Endpoint(id = "maintenance")
public class MaintenanceEndpoint {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceEndpoint.class);
    private final JdbcTemplate jdbc;

    public MaintenanceEndpoint(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @WriteOperation
    public Map<String, Object> run(String operation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);

        String sql = switch (operation) {
            case "vacuum"        -> "VACUUM ANALYZE";
            case "vacuumFull"    -> "VACUUM FULL ANALYZE";
            case "vacuumVerbose" -> "VACUUM VERBOSE ANALYZE";
            default -> throw new IllegalArgumentException("Unknown operation: " + operation +
                    ". Allowed: vacuum, vacuumFull, vacuumVerbose");
        };

        log.info("Running database maintenance: {}", sql);
        long start = System.currentTimeMillis();
        // VACUUM cannot run inside a transaction — JdbcTemplate uses auto-commit for execute()
        jdbc.execute(sql);
        long elapsed = System.currentTimeMillis() - start;

        result.put("sql", sql);
        result.put("status", "ok");
        result.put("durationMs", elapsed);
        log.info("Maintenance {} completed in {}ms", operation, elapsed);
        return result;
    }
}
