package com.mirador.resilience;

import java.time.Instant;

/**
 * Read-only DTO representing a ShedLock entry from the {@code shedlock} table.
 */
public record ScheduledJobDto(
        String name,
        Instant lockUntil,
        Instant lockedAt,
        String lockedBy) {
}
