package com.mirador.resilience;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScheduledJobController}. Verifies the row-mapping logic
 * without needing a real Postgres — previously at 33 % coverage because the
 * only integration test that hit the endpoint required a live {@code shedlock}
 * table.
 */
class ScheduledJobControllerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ScheduledJobController controller = new ScheduledJobController(jdbc);

    @Test
    void getJobs_delegatesToJdbc_andReturnsMappedRows() {
        ScheduledJobDto stats = new ScheduledJobDto(
                "customer-stats",
                Instant.parse("2026-04-18T08:00:00Z"),
                Instant.parse("2026-04-18T07:59:55Z"),
                "host-a");
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(stats));

        List<ScheduledJobDto> result = controller.getJobs();

        assertThat(result).containsExactly(stats);
        verify(jdbc).query(anyString(), any(RowMapper.class));
    }

    @Test
    void rowMapper_readsAllFourColumns() throws Exception {
        // Exercise the lambda row-mapper with a mocked ResultSet so the conversion
        // logic (Timestamp.toInstant chain) is not silently skipped by Mockito.
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("name")).thenReturn("stats");
        when(rs.getString("locked_by")).thenReturn("host-b");
        Timestamp until = Timestamp.from(Instant.parse("2026-04-18T09:00:00Z"));
        Timestamp at = Timestamp.from(Instant.parse("2026-04-18T08:59:00Z"));
        when(rs.getTimestamp("lock_until")).thenReturn(until);
        when(rs.getTimestamp("locked_at")).thenReturn(at);

        // Capture the row mapper the controller passes to JdbcTemplate.
        org.mockito.ArgumentCaptor<RowMapper<ScheduledJobDto>> captor =
                org.mockito.ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), captor.capture())).thenReturn(List.of());

        controller.getJobs();
        ScheduledJobDto dto = captor.getValue().mapRow(rs, 0);

        assertThat(dto).isNotNull();
        assertThat(dto.name()).isEqualTo("stats");
        assertThat(dto.lockedBy()).isEqualTo("host-b");
        assertThat(dto.lockUntil()).isEqualTo(until.toInstant());
        assertThat(dto.lockedAt()).isEqualTo(at.toInstant());
    }
}
