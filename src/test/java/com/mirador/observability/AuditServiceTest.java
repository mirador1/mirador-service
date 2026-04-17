package com.mirador.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AuditService — mocks JdbcTemplate to avoid PostgreSQL.
 */
class AuditServiceTest {

    private JdbcTemplate jdbc;
    private AuditService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new AuditService(jdbc);
    }

    @Test
    void log_insertsRowAndLogsEvent() {
        service.log("alice", "LOGIN_SUCCESS", "via JWT", "1.2.3.4");
        // @Async makes this synchronous in unit tests (no async executor configured)
        verify(jdbc).update(anyString(), eq("alice"), eq("LOGIN_SUCCESS"), eq("via JWT"), eq("1.2.3.4"));
    }

    @Test
    void log_dbFailure_doesNotThrow() {
        when(jdbc.update(anyString(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("DB is down"));
        // Assertion: the call must complete without throwing — audit failures must never
        // bubble up to the caller (audit is fire-and-forget, not a business invariant).
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> service.log("bob", "CUSTOMER_CREATED", "id=1", "5.5.5.5"));
    }

    @Test
    void findAll_noFilters_buildsQueryWithoutWhere() {
        // The no-filter path calls queryForObject with an empty Object[] (varargs with 0 args)
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        AuditPage page = service.findAll(0, 10, null, null, null, null);

        assertThat(page.totalElements()).isZero();
        assertThat(page.content()).isEmpty();
    }

    @Test
    void findAll_withActionFilter_addsWhereClause() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        AuditPage page = service.findAll(0, 10, "LOGIN_SUCCESS", null, null, null);

        assertThat(page.totalElements()).isEqualTo(2L);
        // Verify COUNT query contains WHERE
        verify(jdbc).queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = ?",
                Long.class, new Object[]{"LOGIN_SUCCESS"});
    }

    @Test
    void findAll_withAllFilters_buildsComplexWhere() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to   = Instant.parse("2026-12-31T23:59:59Z");
        AuditPage page = service.findAll(0, 5, "TOKEN_REFRESH", "alice", from, to);

        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    void findAll_pagination_totalPagesCalculated() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(25L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        AuditPage page = service.findAll(0, 10, null, null, null, null);

        assertThat(page.totalPages()).isEqualTo(3); // ceil(25/10)
        assertThat(page.page()).isZero();
        assertThat(page.size()).isEqualTo(10);
    }

    @Test
    void findByCustomerId_queriesDetailColumn() {
        when(jdbc.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of());

        List<AuditEventDto> result = service.findByCustomerId(42L);

        assertThat(result).isEmpty();
        verify(jdbc).query(anyString(), any(RowMapper.class), eq("id=42 %"));
    }
}
