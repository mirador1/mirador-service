package com.mirador.mcp.logs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.mirador.mcp.dto.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LogsService}. The service is a thin filter on top
 * of the appender — these tests check the bounded-limit logic and pass-
 * through to {@link LogbackRingBufferAppender#filteredSnapshot}.
 */
class LogsServiceTest {

    private LogbackRingBufferAppender appender;
    private LogsService service;

    @BeforeEach
    void setUp() {
        appender = new LogbackRingBufferAppender();
        appender.clear();
        service = new LogsService(appender);
    }

    @Test
    void zeroLimitFallsBackToDefault() {
        assertThat(LogsService.effectiveLimit(0)).isEqualTo(LogsService.DEFAULT_LIMIT);
    }

    @Test
    void negativeLimitFallsBackToDefault() {
        assertThat(LogsService.effectiveLimit(-100)).isEqualTo(LogsService.DEFAULT_LIMIT);
    }

    @Test
    void hugeLimitIsClippedToMax() {
        assertThat(LogsService.effectiveLimit(99_999)).isEqualTo(LogsService.MAX_LIMIT);
    }

    @Test
    void tailLogsReturnsAllEventsWhenNoFilter() {
        appender.append(event("a", Level.INFO));
        appender.append(event("b", Level.INFO));

        List<LogEvent> tail = service.tailLogs(50, null, null);
        assertThat(tail).extracting(LogEvent::message).containsExactly("a", "b");
    }

    @Test
    void tailLogsAppliesLevelFilter() {
        appender.append(event("info", Level.INFO));
        appender.append(event("err", Level.ERROR));

        List<LogEvent> tail = service.tailLogs(50, "ERROR", null);
        assertThat(tail).hasSize(1);
        assertThat(tail.get(0).level()).isEqualTo("ERROR");
    }

    @Test
    void tailLogsAppliesRequestIdFilter() {
        Map<String, String> mdc = new HashMap<>();
        mdc.put("request_id", "rid-1");
        appender.append(eventWithMdc("a", Level.INFO, mdc));
        appender.append(event("b", Level.INFO));

        List<LogEvent> tail = service.tailLogs(50, null, "rid-1");
        assertThat(tail).hasSize(1);
        assertThat(tail.get(0).requestId()).isEqualTo("rid-1");
    }

    private ILoggingEvent event(String msg, Level level) {
        return eventWithMdc(msg, level, Map.of());
    }

    private ILoggingEvent eventWithMdc(String msg, Level level, Map<String, String> mdc) {
        LoggingEvent ev = new LoggingEvent();
        ev.setLoggerName("test");
        ev.setLevel(level);
        ev.setMessage(msg);
        ev.setTimeStamp(System.currentTimeMillis());
        ev.setMDCPropertyMap(new HashMap<>(mdc));
        return ev;
    }
}
