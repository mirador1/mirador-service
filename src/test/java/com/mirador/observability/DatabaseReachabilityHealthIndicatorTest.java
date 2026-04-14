package com.mirador.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PostgreSQL reachability health indicator.
 */
class DatabaseReachabilityHealthIndicatorTest {

    @Test
    void selectOne_returnsOne_reportsUp() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("select 1"), eq(Integer.class))).thenReturn(1);

        var indicator = new DatabaseReachabilityHealthIndicator(jdbc);
        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails().get("database")).isEqualTo("reachable");
    }

    @Test
    void selectOne_returnsUnexpectedValue_reportsDown() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("select 1"), eq(Integer.class))).thenReturn(99);

        var indicator = new DatabaseReachabilityHealthIndicator(jdbc);
        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails().get("database")).isEqualTo("unexpected response");
    }

    @Test
    void selectOne_returnsNull_reportsDown() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("select 1"), eq(Integer.class))).thenReturn(null);

        var indicator = new DatabaseReachabilityHealthIndicator(jdbc);
        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void jdbcException_reportsDown() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(any(), any(Class.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        var indicator = new DatabaseReachabilityHealthIndicator(jdbc);
        Health h = indicator.health();

        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails().get("database")).isEqualTo("unreachable");
    }
}
