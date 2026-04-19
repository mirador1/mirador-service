package com.mirador.diag;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the duration of each Spring bean's initialisation into a lazy
 * signal-like structure, plus the total {@link ApplicationReadyEvent}
 * elapsed time. Exposed by {@link StartupTimingsController}.
 *
 * <p>The data answers "where did the first 4 seconds of boot go?". Useful
 * in live demos to explain why the Spring Boot startup cost what it cost
 * — Flyway migrations, Kafka listener registration, circuit-breaker
 * wiring, OpenTelemetry agent attach, etc. each become a visible line.
 *
 * <p>Implementation: a {@link BeanPostProcessor} stamps the pre- and
 * post-init timestamps and records the delta. Only beans that took
 * ≥ {@value #RECORD_THRESHOLD_MS} ms are kept — the rest are noise and
 * would drown out the signal. Kept in insertion order so the report
 * reads like the real boot sequence.
 *
 * <p>This is dev / ops observability, not application logic: there is
 * zero effect on business behaviour, and it adds ~1 µs per bean at
 * init time (cheap System.nanoTime() pair).
 */
@Component
public class StartupTimings implements BeanPostProcessor {

    /** Below this threshold a bean's init timing is noise — drop it. */
    private static final long RECORD_THRESHOLD_MS = 5;

    /** Keyed by bean name, value is the init duration. Insertion-ordered. */
    private final Map<String, Long> beanTimingsMs = new LinkedHashMap<>();

    /** Transient store of pre-init nanoTime for the currently-initialising bean. */
    private final ThreadLocal<Long> initStart = new ThreadLocal<>();

    /** Wall-clock total from JVM start to ApplicationReadyEvent. Set once. */
    private volatile long totalBootMs = -1;

    /** When the ApplicationReadyEvent fired. Null until boot completes. */
    private volatile Instant readyAt;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        initStart.set(System.nanoTime());
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        Long start = initStart.get();
        initStart.remove();
        if (start == null) {
            return bean;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (elapsedMs >= RECORD_THRESHOLD_MS) {
            beanTimingsMs.put(beanName, elapsedMs);
        }
        return bean;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        this.readyAt = Instant.now();
        // Spring's timestamp is in millis since the context refresh started.
        // Duration since process start is the more honest number to show.
        long jvmStartMs = java.lang.management.ManagementFactory
                .getRuntimeMXBean().getStartTime();
        this.totalBootMs = System.currentTimeMillis() - jvmStartMs;
    }

    /** Read-only snapshot for the controller. Insertion-ordered. */
    public Map<String, Long> beanTimings() {
        return new LinkedHashMap<>(beanTimingsMs);
    }

    /** JVM-start → ApplicationReadyEvent wall-clock time, in millis. -1 until ready. */
    public long totalBootMs() {
        return totalBootMs;
    }

    /** Instant the ApplicationReadyEvent fired. Null until ready. */
    public Instant readyAt() {
        return readyAt;
    }

    /** Convenience: the total boot time as a Duration or Duration.ZERO if not ready yet. */
    public Duration totalBootDuration() {
        return totalBootMs < 0 ? Duration.ZERO : Duration.ofMillis(totalBootMs);
    }
}
