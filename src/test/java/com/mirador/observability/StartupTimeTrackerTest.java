package com.mirador.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StartupTimeTracker} — pins the JVM-start to
 * ApplicationReady duration calculation.
 *
 * <p>Pinned contracts:
 *   - getStartupDurationMs() returns 0 BEFORE ApplicationReadyEvent fires
 *     (sentinel for "still booting" — the QualityReportEndpoint renders
 *     this as a "—" rather than a misleading 0ms)
 *   - onApplicationReady() computes (event.timestamp - JVM start time)
 *     in milliseconds — matches Spring Boot's "Started in X.X seconds" log
 */
class StartupTimeTrackerTest {

    @Test
    void getStartupDurationMs_returnsZeroBeforeApplicationReady() {
        // Pinned: 0 is the sentinel for "boot not yet complete" — the
        // dashboard's quality endpoint renders 0 differently from a real
        // measurement (so devs don't see "0 ms" and assume instant boot
        // when in fact the measurement hasn't happened).
        StartupTimeTracker tracker = new StartupTimeTracker();

        assertThat(tracker.getStartupDurationMs()).isZero();
    }

    @Test
    void onApplicationReady_computesDurationFromJvmStart() {
        // Pinned: duration = event.timestamp - JVM start time. Matches
        // Spring Boot's "Started MiradorApplication in X.XXX seconds"
        // log line — both reference JVM start as the zero point.
        StartupTimeTracker tracker = new StartupTimeTracker();

        // Simulate ready event firing 5 seconds AFTER JVM start.
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
        long simulatedReadyAt = jvmStart + 5_000;
        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        when(event.getTimestamp()).thenReturn(simulatedReadyAt);

        tracker.onApplicationReady(event);

        assertThat(tracker.getStartupDurationMs()).isEqualTo(5_000);
    }

    @Test
    void onApplicationReady_overwritesPreviousMeasurement_lastWriteWins() {
        // Defensive : if onApplicationReady fires twice (e.g. context
        // refresh in tests), the latest measurement wins. Not a typical
        // case but the field is mutable so this is the contract.
        StartupTimeTracker tracker = new StartupTimeTracker();
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();

        ApplicationReadyEvent first = mock(ApplicationReadyEvent.class);
        when(first.getTimestamp()).thenReturn(jvmStart + 1_000);
        tracker.onApplicationReady(first);
        assertThat(tracker.getStartupDurationMs()).isEqualTo(1_000);

        ApplicationReadyEvent second = mock(ApplicationReadyEvent.class);
        when(second.getTimestamp()).thenReturn(jvmStart + 7_500);
        tracker.onApplicationReady(second);
        assertThat(tracker.getStartupDurationMs()).isEqualTo(7_500);
    }

    @Test
    void onApplicationReady_handlesNegativeDuration_clockSkew() {
        // Pinned: defensive against clock skew. event.getTimestamp() is
        // wall-clock; JVM start is also wall-clock from the JVM's
        // perspective. NTP drift mid-boot could in theory produce a
        // negative duration. The current implementation just stores
        // whatever the math gives — this test pins that we DON'T
        // silently coerce to 0 (which would mask the skew bug).
        StartupTimeTracker tracker = new StartupTimeTracker();
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();

        ApplicationReadyEvent event = mock(ApplicationReadyEvent.class);
        when(event.getTimestamp()).thenReturn(jvmStart - 100); // 100 ms before JVM start

        tracker.onApplicationReady(event);

        // Pinned: -100 (NOT coerced to 0) — surfaces the skew issue
        // rather than hiding it. The dashboard renders negative as
        // "clock skew detected" rather than silently displaying 0ms.
        assertThat(tracker.getStartupDurationMs()).isEqualTo(-100);
    }
}
