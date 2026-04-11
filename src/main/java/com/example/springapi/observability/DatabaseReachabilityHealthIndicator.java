package com.example.springapi.observability;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Custom Spring Boot health indicator that verifies PostgreSQL reachability.
 *
 * <p>Spring Boot Actuator exposes health checks at {@code /actuator/health}.
 * Liveness and readiness probes can be found at:
 * <ul>
 *   <li>{@code /actuator/health/liveness} — is the process alive?</li>
 *   <li>{@code /actuator/health/readiness} — is the service ready to accept traffic?</li>
 * </ul>
 *
 * <p>This indicator is registered under the name {@code dbReachability} (the {@code @Component}
 * value), so it appears as a sub-component of the readiness group and can be checked individually
 * at {@code /actuator/health/dbReachability}.
 *
 * <h3>Probe logic</h3>
 * <p>A lightweight {@code SELECT 1} query is issued via {@link JdbcTemplate}. This verifies:
 * <ul>
 *   <li>The JDBC connection pool can acquire a connection.</li>
 *   <li>PostgreSQL is running and the network path is open.</li>
 *   <li>The database user has at least SELECT privilege.</li>
 * </ul>
 * Any exception (connection refused, timeout, authentication failure) causes the indicator
 * to return {@code DOWN} with the exception detail, which drives Kubernetes readiness probe
 * failures and removes the pod from the load-balancer rotation.
 *
 * <h3>Diagnostic scenario</h3>
 * <p>To simulate a DB outage: stop PostgreSQL with {@code docker compose stop db},
 * then poll {@code GET /actuator/health/readiness} to observe the status change.
 */
@Component("dbReachability")
public class DatabaseReachabilityHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseReachabilityHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Executes {@code SELECT 1} and returns {@code UP} if the result is {@code 1},
     * {@code DOWN} with details otherwise.
     */
    @Override
    public Health health() {
        try {
            Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
            if (result != null && result == 1) {
                return Health.up()
                        .withDetail("database", "reachable")
                        .build();
            }
            return Health.down()
                    .withDetail("database", "unexpected response")
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("database", "unreachable")
                    .build();
        }
    }
}