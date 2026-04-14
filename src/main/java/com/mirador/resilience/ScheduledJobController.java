package com.mirador.resilience;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller exposing ShedLock job state at {@code GET /scheduled/jobs}.
 *
 * <p>Queries the {@code shedlock} table via {@link JdbcTemplate} and returns the
 * last known execution state for each registered scheduled task. Requires authentication.
 */
@RestController
@RequestMapping("/scheduled/jobs")
@PreAuthorize("isAuthenticated()")
public class ScheduledJobController {

    private final JdbcTemplate jdbc;

    public ScheduledJobController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns all rows from the {@code shedlock} table.
     * Each row represents the last lock state for a scheduled job.
     */
    @GetMapping
    public List<ScheduledJobDto> getJobs() {
        return jdbc.query(
                "SELECT name, lock_until, locked_at, locked_by FROM shedlock ORDER BY name",
                (rs, rowNum) -> new ScheduledJobDto(
                        rs.getString("name"),
                        rs.getTimestamp("lock_until").toInstant(),
                        rs.getTimestamp("locked_at").toInstant(),
                        rs.getString("locked_by")));
    }
}
