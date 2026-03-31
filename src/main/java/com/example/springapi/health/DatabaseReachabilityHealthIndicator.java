package com.example.springapi.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("dbReachability")
public class DatabaseReachabilityHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseReachabilityHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

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