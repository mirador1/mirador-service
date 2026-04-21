package com.mirador.resilience;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
// `@Tag` declared at the class level so Spectral's `operation-tag-defined` rule
// finds the tag string referenced by the auto-generated tags entry on the
// `GET /scheduled/jobs` operation. Without this, the openapi.json carries a
// `tags: ["scheduled-job-controller"]` (springdoc default from the class name)
// that no top-level `tags:` block declares — `oas3-schema` accepts it but
// `operation-tag-defined` flags it as a warning.
@Tag(name = "Scheduled jobs", description = "Inspect ShedLock distributed-lock state for scheduled jobs.")
public class ScheduledJobController {

    private final JdbcTemplate jdbc;

    public ScheduledJobController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns all rows from the {@code shedlock} table.
     * Each row represents the last lock state for a scheduled job.
     */
    @Operation(summary = "List ShedLock-tracked scheduled jobs",
            description = "Returns one entry per scheduled job registered with ShedLock, including the last lock holder + lock-until timestamp. Useful to verify that scheduled tasks are not silently failing or deadlocked across replicas. Read-only; queries the `shedlock` table directly via JdbcTemplate.")
    @ApiResponse(responseCode = "200", description = "List of `{name, lockUntil, lockedAt, lockedBy}` rows from the shedlock table.")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token (this endpoint requires authentication)")
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
