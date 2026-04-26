package com.mirador.mcp.logs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.mirador.mcp.dto.LogEvent;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogbackRingBufferAppender}.
 *
 * <p>The appender is exercised programmatically — no Spring context, no
 * SLF4J binding mutation : we synthesize {@link LoggingEvent} instances
 * and call {@link LogbackRingBufferAppender#append} directly. This keeps
 * the test isolated from the global Logback configuration.
 */
class LogbackRingBufferAppenderTest {

    @Test
    void capacityCapsBufferAndEvictsOldest() {
        LogbackRingBufferAppender appender = new LogbackRingBufferAppender(3);

        for (int i = 0; i < 5; i++) {
            appender.append(buildEvent("msg-" + i, Level.INFO, Map.of()));
        }

        List<LogEvent> snapshot = appender.snapshot();
        assertThat(snapshot).hasSize(3);
        assertThat(snapshot.get(0).message()).isEqualTo("msg-2");
        assertThat(snapshot.get(2).message()).isEqualTo("msg-4");
    }

    @Test
    void invalidCapacityFallsBackToDefault() {
        LogbackRingBufferAppender appender = new LogbackRingBufferAppender(0);
        assertThat(appender.capacity()).isEqualTo(LogbackRingBufferAppender.DEFAULT_CAPACITY);
    }

    @Test
    void mdcRequestIdAndTraceIdAreCarriedOnTheEvent() {
        LogbackRingBufferAppender appender = new LogbackRingBufferAppender();
        Map<String, String> mdc = new HashMap<>();
        mdc.put("request_id", "req-42");
        mdc.put("trace_id", "trace-99");

        appender.append(buildEvent("with-mdc", Level.WARN, mdc));

        LogEvent event = appender.snapshot().get(0);
        assertThat(event.requestId()).isEqualTo("req-42");
        assertThat(event.traceId()).isEqualTo("trace-99");
        assertThat(event.level()).isEqualTo("WARN");
    }

    @Test
    void appendIgnoresNullEvent() {
        LogbackRingBufferAppender appender = new LogbackRingBufferAppender();
        appender.append(null);
        assertThat(appender.size()).isZero();
    }

    @Test
    void filterByLevelExcludesOtherLevels() {
        LogbackRingBufferAppender appender = new LogbackRingBufferAppender();
        appender.append(buildEvent("info-1", Level.INFO, Map.of()));
        appender.append(buildEvent("warn-1", Level.WARN, Map.of()));
        appender.append(buildEvent("error-1", Level.ERROR, Map.of()));

        List<LogEvent> warns = appender.filteredSnapshot("warn", null, 100);
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).message()).isEqualTo("warn-1");
    }

    @Test
    void filterByRequestIdMatchesEitherRequestOrTraceId() {
        LogbackRingBufferAppender appender = new LogbackRingBufferAppender();
        appender.append(buildEvent("a", Level.INFO, Map.of("request_id", "rid-1")));
        appender.append(buildEvent("b", Level.INFO, Map.of("trace_id", "tid-2")));
        appender.append(buildEvent("c", Level.INFO, Map.of()));

        assertThat(appender.filteredSnapshot(null, "rid-1", 100))
                .extracting(LogEvent::message).containsExactly("a");
        assertThat(appender.filteredSnapshot(null, "tid-2", 100))
                .extracting(LogEvent::message).containsExactly("b");
    }

    @Test
    void clearEmptiesTheBuffer() {
        LogbackRingBufferAppender appender = new LogbackRingBufferAppender();
        appender.append(buildEvent("x", Level.INFO, Map.of()));
        appender.clear();
        assertThat(appender.size()).isZero();
    }

    /**
     * Builds a Logback {@link LoggingEvent} with the supplied message,
     * level, and MDC. Goes through {@link LoggerContext} so the MDC
     * snapshotting actually fires.
     */
    private ILoggingEvent buildEvent(String msg, Level level, Map<String, String> mdc) {
        LoggerContext ctx = new LoggerContext();
        LoggingEvent ev = new LoggingEvent();
        ev.setLoggerContext(ctx);
        ev.setLoggerName(getClass().getName());
        ev.setLevel(level);
        ev.setMessage(msg);
        ev.setTimeStamp(System.currentTimeMillis());
        ev.setMDCPropertyMap(new HashMap<>(mdc));
        return ev;
    }
}
