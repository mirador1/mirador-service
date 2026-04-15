package com.mirador.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

/**
 * Captures the Spring Boot startup duration (time from JVM launch to ApplicationReady).
 *
 * <p>Measured as: {@code ApplicationReadyEvent.timestamp - JVM start time}.
 * This matches the value printed by Spring Boot: "Started MiradorApplication in X.XXX seconds"
 * — both use the JVM start time as the reference point.
 *
 * <p>Exposed as a Spring bean so {@link QualityReportEndpoint} can include startup time
 * in the runtime section of {@code /actuator/quality}, making it visible in the Angular
 * quality dashboard without parsing log files.
 */
@Component
public class StartupTimeTracker {

    private static final Logger log = LoggerFactory.getLogger(StartupTimeTracker.class);

    /** Startup duration in milliseconds; 0 until ApplicationReadyEvent fires. */
    private long startupDurationMs = 0;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        // event.getTimestamp() is the wall-clock time when ApplicationReadyEvent was published.
        // JVM start time is when the JVM process itself launched — before Spring even loads.
        long jvmStartMs = ManagementFactory.getRuntimeMXBean().getStartTime();
        startupDurationMs = event.getTimestamp() - jvmStartMs;
        log.info("startup_complete duration_ms={}", startupDurationMs);
    }

    /** Returns the startup duration in milliseconds, or 0 if called before the app is ready. */
    public long getStartupDurationMs() {
        return startupDurationMs;
    }
}
