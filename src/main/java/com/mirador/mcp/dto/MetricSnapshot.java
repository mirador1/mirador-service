package com.mirador.mcp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * Single metric sample surfaced by {@code get_metrics} MCP tool.
 *
 * <p>Captured directly from the Micrometer {@code MeterRegistry} bean
 * IN-PROCESS — NO Mimir / Prometheus HTTP call. The {@link #type()} value
 * mirrors the Micrometer {@code Meter.Type} enum
 * ({@code COUNTER / GAUGE / TIMER / DISTRIBUTION_SUMMARY / LONG_TASK_TIMER /
 * OTHER}) so consumers know how to interpret {@link #value()}.
 *
 * <p>For composite meters (Timer, DistributionSummary), {@code value} carries
 * the most relevant single number (count for Counter, value for Gauge, mean
 * for Timer in seconds, mean for DistributionSummary). For richer needs the
 * caller should issue follow-up calls or read the actuator directly.
 *
 * @param name      metric name (e.g. {@code http.server.requests})
 * @param tags      tags / labels as a flat string map (e.g. {@code uri=/customers, method=GET})
 * @param type      Micrometer meter type (uppercase)
 * @param value     primary numeric value (semantics depend on {@link #type()})
 * @param timestamp instant the snapshot was taken
 */
@Schema(description = "Single Micrometer meter sample (in-process registry, NO Mimir call)")
public record MetricSnapshot(
        String name,
        Map<String, String> tags,
        String type,
        double value,
        Instant timestamp
) {
}
