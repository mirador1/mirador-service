package com.mirador.mcp.logs;

import com.mirador.mcp.dto.LogEvent;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MCP tool surface for in-process log retrieval.
 *
 * <p>Backed by the {@link LogbackRingBufferAppender} attached at startup.
 * Per ADR-0062 § Observability tools — backend-LOCAL only : NO Loki HTTP,
 * NO external client. The Spring Boot jar runs identically on a laptop
 * with no observability infra around.
 *
 * <p>The tool is read-only and safe for any authenticated role.
 */
@Service
public class LogsService {

    /**
     * Hard ceiling on the number of events returned in a single call —
     * matches the buffer capacity by default ; even a permissive caller
     * cannot drain more than what's actually retained.
     */
    public static final int MAX_LIMIT = LogbackRingBufferAppender.DEFAULT_CAPACITY;

    /**
     * Default page size when {@code n} is omitted or non-positive.
     * Chosen so the LLM gets enough context (~50 lines) without flooding
     * its context window with irrelevant heartbeats.
     */
    public static final int DEFAULT_LIMIT = 50;

    private final LogbackRingBufferAppender appender;

    public LogsService(LogbackRingBufferAppender appender) {
        this.appender = appender;
    }

    /**
     * Returns the most recent log events from the in-process ring buffer,
     * optionally filtered by level and request/trace ID.
     *
     * @param n         max events to return ; 1..500. Values ≤ 0 fall back
     *                  to {@link #DEFAULT_LIMIT} ; values &gt; {@link #MAX_LIMIT}
     *                  are capped at {@link #MAX_LIMIT}.
     * @param level     SLF4J level filter ({@code INFO / WARN / ERROR}) ;
     *                  case-insensitive ; {@code null} = no constraint.
     * @param requestId exact match on the {@code request_id} or trace ID
     *                  MDC value — same identifier the {@code RequestIdFilter}
     *                  generates per HTTP call ; {@code null} = no constraint.
     * @return list of {@link LogEvent}, oldest first, never {@code null}.
     */
    @Tool(name = "tail_logs",
            description = "Returns the most recent in-process log events. Backed by a Logback "
                    + "ring buffer (last 500 events) — NO Loki call, NO external HTTP. "
                    + "Use to triage a recent error, find log lines for a specific request "
                    + "ID, or sample WARN/ERROR activity. Filter by level and/or request "
                    + "ID for targeted investigation.")
    public List<LogEvent> tailLogs(
            @ToolParam(description = "Max events returned, 1..500 (default 50). Larger "
                    + "values give more context but may exceed the LLM's window.")
            int n,
            @ToolParam(required = false, description = "SLF4J level filter — INFO, WARN, "
                    + "ERROR, DEBUG, TRACE. Omit to get all levels.")
            String level,
            @ToolParam(required = false, description = "Exact request ID or trace ID. Omit "
                    + "to skip the filter. Useful to follow a single request across log lines.")
            String requestId
    ) {
        int effective = effectiveLimit(n);
        return appender.filteredSnapshot(level, requestId, effective);
    }

    /**
     * Resolves the user-supplied {@code n} to a sane bounded value.
     * Extracted for unit testing and reuse by the integration test.
     */
    static int effectiveLimit(int n) {
        if (n <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(n, MAX_LIMIT);
    }
}
