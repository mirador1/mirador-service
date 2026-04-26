package com.mirador.mcp.logs;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.mirador.mcp.dto.LogEvent;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * In-memory bounded ring buffer Logback appender.
 *
 * <p>Backs the {@code tail_logs} MCP tool : retains the last {@link #capacity()}
 * Logback events in a thread-safe deque ; older events are evicted when the
 * buffer is full. Per ADR-0062 § Observability tools — backend-LOCAL only :
 * NO Loki, NO external HTTP. The Spring Boot jar must run identically on a
 * laptop with no infra around.
 *
 * <h3>Why a custom appender, not the existing OTEL one ?</h3>
 * <ul>
 *   <li>The OTEL appender pushes to Loki — it doesn't retain anything for
 *       in-process queries.</li>
 *   <li>Reading back from Loki via HTTP would couple the backend to Loki
 *       reachability — explicitly rejected by the ADR.</li>
 *   <li>A bounded ring buffer in heap is cheap : 500 events × ~500 B avg =
 *       ~250 KB heap footprint.</li>
 * </ul>
 *
 * <h3>Concurrency</h3>
 * <p>Logback's {@link AppenderBase} synchronizes calls to {@link #append}
 * by default (the {@code AppenderBase.doAppend} entry path uses a
 * {@code ReentrantLock}). The internal {@link ArrayDeque} is therefore
 * accessed under that lock. Reads ({@link #snapshot}) acquire a separate
 * monitor on the deque to copy out a consistent slice without blocking
 * appenders for long.
 *
 * <h3>Filtering</h3>
 * <p>The appender stores ALL events at the configured root level (INFO by
 * default). Per-call level / requestId / traceId filtering is done in
 * {@link LogsService}, not here — the appender stays a dumb buffer.
 */
public class LogbackRingBufferAppender extends AppenderBase<ILoggingEvent> {

    /**
     * Default capacity — chosen to bound heap usage to ~250 KB while
     * keeping enough recent context for an LLM to spot patterns.
     */
    public static final int DEFAULT_CAPACITY = 500;

    /**
     * Hard cap on log message length stored in the buffer. Prevents a
     * runaway error stack trace from blowing the per-event budget.
     */
    private static final int MAX_MESSAGE_LENGTH = 8_000;

    /**
     * MDC keys recognised as carrying the originating request identifier
     * (set by {@code RequestIdFilter}) — first match wins.
     */
    private static final List<String> REQUEST_ID_KEYS = List.of("request_id", "requestId", "X-Request-Id");

    /**
     * MDC keys recognised as carrying the OpenTelemetry trace ID (set by
     * the OpenTelemetry SDK auto-instrumentation).
     */
    private static final List<String> TRACE_ID_KEYS = List.of("trace_id", "traceId", "trace.id");

    private final Deque<LogEvent> buffer;
    private final int capacity;

    /** Default ctor — uses {@link #DEFAULT_CAPACITY}. */
    public LogbackRingBufferAppender() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Builds a buffer with the given capacity.
     *
     * @param capacity max events retained ; must be ≥ 1 (smaller values
     *                 fall back to {@link #DEFAULT_CAPACITY} to avoid a
     *                 silent no-op)
     */
    public LogbackRingBufferAppender(int capacity) {
        this.capacity = capacity > 0 ? capacity : DEFAULT_CAPACITY;
        this.buffer = new ArrayDeque<>(this.capacity);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        LogEvent evt = toLogEvent(event);
        synchronized (buffer) {
            if (buffer.size() >= capacity) {
                buffer.removeFirst();
            }
            buffer.addLast(evt);
        }
    }

    /**
     * Returns a defensive copy of the buffer (newest last).
     *
     * @return immutable list snapshot ; empty when the buffer is empty
     */
    public List<LogEvent> snapshot() {
        synchronized (buffer) {
            return List.copyOf(buffer);
        }
    }

    /**
     * Clears the buffer. Used by unit tests to enforce a known starting
     * state ; never called from production paths.
     */
    public void clear() {
        synchronized (buffer) {
            buffer.clear();
        }
    }

    /** @return configured capacity (max events retained). */
    public int capacity() {
        return capacity;
    }

    /** @return current number of events stored (≤ {@link #capacity()}). */
    public int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    /**
     * Adapts a Logback event to the immutable {@link LogEvent} record.
     * Truncates oversized messages, picks the first matching MDC key for
     * request / trace identifiers.
     */
    private LogEvent toLogEvent(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        String message = safeMessage(event.getFormattedMessage());
        return new LogEvent(
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString().toUpperCase(Locale.ROOT),
                event.getLoggerName(),
                message,
                firstMdcMatch(mdc, REQUEST_ID_KEYS),
                firstMdcMatch(mdc, TRACE_ID_KEYS)
        );
    }

    /**
     * Truncates messages above {@link #MAX_MESSAGE_LENGTH} ; preserves the
     * suffix so the LLM still sees the latest context.
     */
    private String safeMessage(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= MAX_MESSAGE_LENGTH) {
            return raw;
        }
        return raw.substring(0, MAX_MESSAGE_LENGTH) + "…[truncated]";
    }

    /**
     * Returns the first MDC value found among the candidate keys ;
     * {@code null} when none is set or the map itself is null.
     */
    private String firstMdcMatch(Map<String, String> mdc, List<String> keys) {
        if (mdc == null || mdc.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            String value = mdc.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * Filters the snapshot by the supplied criteria (any of which may be
     * {@code null} = "no constraint"). Matches happen client-side on the
     * already-bounded copy.
     *
     * @param level     SLF4J level ({@code INFO / WARN / ERROR}) ; case-insensitive
     * @param requestId exact match on {@link LogEvent#requestId()} or {@link LogEvent#traceId()}
     * @param limit     max events to return (newest last)
     */
    public List<LogEvent> filteredSnapshot(String level, String requestId, int limit) {
        List<LogEvent> all = snapshot();
        if (level == null && requestId == null) {
            return tail(all, limit);
        }
        String levelUpper = level == null ? null : level.toUpperCase(Locale.ROOT);
        List<LogEvent> filtered = new ArrayList<>();
        for (LogEvent e : all) {
            if (levelUpper != null && !levelUpper.equals(e.level())) {
                continue;
            }
            if (requestId != null && !requestId.equals(e.requestId()) && !requestId.equals(e.traceId())) {
                continue;
            }
            filtered.add(e);
        }
        return tail(filtered, limit);
    }

    /** Tail-N convenience used by both code paths above. */
    private List<LogEvent> tail(List<LogEvent> all, int limit) {
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }
}
