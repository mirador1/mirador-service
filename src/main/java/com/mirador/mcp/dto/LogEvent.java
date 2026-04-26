package com.mirador.mcp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Single log event surfaced by {@code tail_logs} MCP tool.
 *
 * <p>Captured by the in-memory {@code LogbackRingBufferAppender} attached
 * at startup. {@link #requestId()} and {@link #traceId()} are populated
 * from the Logback MDC (Mapped Diagnostic Context) when the originating
 * request set them ; {@code null} otherwise (e.g. background tasks).
 *
 * <p>Per ADR-0062 § Observability tools — backend-LOCAL only : the source
 * is Logback's append pipeline IN-PROCESS. NO Loki HTTP call, NO external
 * client. The application stays infrastructure-agnostic.
 *
 * @param timestamp ISO-8601 instant the event was logged
 * @param level     SLF4J level — {@code TRACE / DEBUG / INFO / WARN / ERROR}
 * @param logger    fully qualified logger name (typically the source class)
 * @param message   formatted log message (placeholders already substituted)
 * @param requestId X-Request-Id header value if the call originated from HTTP
 * @param traceId   OpenTelemetry trace ID for cross-service correlation
 */
@Schema(description = "Single log event from the in-process ring buffer")
public record LogEvent(
        Instant timestamp,
        String level,
        String logger,
        String message,
        String requestId,
        String traceId
) {
}
