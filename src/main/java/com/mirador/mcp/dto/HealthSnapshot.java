package com.mirador.mcp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Composite health snapshot surfaced by {@code get_health} / {@code get_health_detail}.
 *
 * <p>Wraps Spring Boot Actuator's in-process {@code HealthEndpoint} —
 * NO HTTP self-call, NO external infra. The {@code summary} variant carries
 * empty {@link ComponentStatus#details()} maps ; the {@code detail} variant
 * (admin-gated) carries the full diagnostics.
 *
 * @param status     overall composite status — {@code UP / DOWN / OUT_OF_SERVICE / UNKNOWN}
 * @param components per-indicator breakdown ({@code db}, {@code kafka}, {@code redis}, …)
 */
@Schema(description = "Composite health from Actuator HealthEndpoint (in-process)")
public record HealthSnapshot(
        String status,
        Map<String, ComponentStatus> components
) {

    /**
     * Per-indicator status row.
     *
     * @param status  one of {@code UP / DOWN / OUT_OF_SERVICE / UNKNOWN}
     * @param details indicator-specific data (DB validation query, Kafka broker,
     *                Redis ping latency, …) — empty for non-detail callers
     */
    @Schema(description = "Per-indicator health row")
    public record ComponentStatus(
            String status,
            Map<String, Object> details
    ) {
    }
}
