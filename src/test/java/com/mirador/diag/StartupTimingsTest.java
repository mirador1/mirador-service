package com.mirador.diag;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link StartupTimings} BeanPostProcessor — pins the
 * threshold filtering, ordering, and ApplicationReadyEvent state machine.
 *
 * <p>We exercise the BPP contract directly (before/after pairs) instead
 * of standing up a Spring context — faster, deterministic, and the
 * control flow is what matters.
 */
class StartupTimingsTest {

    @Test
    void afterInit_belowThreshold_isNotRecorded() throws Exception {
        // Pinned: the 5ms threshold filters noise — 99 % of beans
        // initialise in < 1 ms (POJOs, simple configs). Recording them
        // all would drown the actual 200 ms culprits in noise. Tests
        // sleep 0 ms then call after — well under the threshold.
        StartupTimings timings = new StartupTimings();
        Object bean = new Object();

        timings.postProcessBeforeInitialization(bean, "fastBean");
        timings.postProcessAfterInitialization(bean, "fastBean");

        assertThat(timings.beanTimings()).doesNotContainKey("fastBean");
    }

    @Test
    void afterInit_atOrAboveThreshold_isRecorded() throws Exception {
        // Pinned: a slow bean (e.g. Flyway, Kafka init) crosses the 5ms
        // threshold and lands in the report. We sleep 7 ms to be safely
        // above the boundary even with sub-ms timer jitter.
        StartupTimings timings = new StartupTimings();
        Object bean = new Object();

        timings.postProcessBeforeInitialization(bean, "slowBean");
        Thread.sleep(7);
        timings.postProcessAfterInitialization(bean, "slowBean");

        assertThat(timings.beanTimings()).containsKey("slowBean");
        assertThat(timings.beanTimings().get("slowBean")).isGreaterThanOrEqualTo(5L);
    }

    @Test
    void afterInit_withoutBefore_doesNotThrow() {
        // Pinned: a bean processed by another BPP that returned a
        // different instance from before() can produce an after() call
        // for an instance we never timed. Must not crash with NPE on
        // the missing ThreadLocal — the bean is just not recorded.
        StartupTimings timings = new StartupTimings();

        timings.postProcessAfterInitialization(new Object(), "phantom");

        assertThat(timings.beanTimings()).doesNotContainKey("phantom");
    }

    @Test
    void beanTimings_preservesInsertionOrder() throws Exception {
        // Pinned: the report reads top-to-bottom in the actual boot
        // sequence — Flyway BEFORE Kafka BEFORE Otel — so the dev can
        // see the cascade. A HashMap-based store would reorder by hash
        // and lose the narrative.
        StartupTimings timings = new StartupTimings();

        recordSlowBean(timings, "first");
        recordSlowBean(timings, "second");
        recordSlowBean(timings, "third");

        assertThat(timings.beanTimings().keySet()).containsExactly("first", "second", "third");
    }

    @Test
    void beanTimings_returnsADefensiveCopy() throws Exception {
        // Pinned: the controller serializes the map for HTTP — if the
        // returned reference were the live map, a serialization race
        // could throw ConcurrentModificationException as another bean
        // initialises mid-render.
        StartupTimings timings = new StartupTimings();
        recordSlowBean(timings, "bean1");

        Map<String, Long> snapshot = timings.beanTimings();
        snapshot.put("injected", 999L);

        assertThat(timings.beanTimings()).doesNotContainKey("injected");
        assertThat(timings.beanTimings()).containsKey("bean1");
    }

    @Test
    void totalBootMs_returnsMinusOneBeforeReady() {
        // Pinned: -1 is the sentinel for "not yet known" — the
        // controller renders this as "still booting" rather than 0,
        // which would falsely imply an instant boot.
        StartupTimings timings = new StartupTimings();

        assertThat(timings.totalBootMs()).isEqualTo(-1L);
        assertThat(timings.readyAt()).isNull();
    }

    @Test
    void totalBootDuration_isZeroBeforeReady_andRealAfter() {
        // Pinned: the helper converts -1 → Duration.ZERO so the dashboard
        // doesn't render a "negative duration" cell. After ready it
        // returns a positive Duration computed from JVM start time.
        StartupTimings timings = new StartupTimings();
        assertThat(timings.totalBootDuration()).isEqualTo(Duration.ZERO);

        timings.onReady(mock(ApplicationReadyEvent.class));

        assertThat(timings.totalBootDuration()).isGreaterThan(Duration.ZERO);
        assertThat(timings.totalBootMs()).isPositive();
        assertThat(timings.readyAt()).isNotNull();
    }

    @Test
    void onReady_setsReadyAtToCurrentInstant() {
        // Pinned: readyAt becomes the wall-clock when the
        // ApplicationReadyEvent fires — used for the dashboard's
        // "Application ready since 12:34:56" line and for monitoring
        // alert correlation. Must NOT remain null after onReady.
        StartupTimings timings = new StartupTimings();
        java.time.Instant before = java.time.Instant.now();

        timings.onReady(mock(ApplicationReadyEvent.class));

        assertThat(timings.readyAt()).isNotNull();
        assertThat(timings.readyAt()).isAfterOrEqualTo(before);
    }

    /** Helper: simulate a slow bean init via sleep, record it. */
    private static void recordSlowBean(StartupTimings timings, String name) throws InterruptedException {
        timings.postProcessBeforeInitialization(new Object(), name);
        Thread.sleep(7);
        timings.postProcessAfterInitialization(new Object(), name);
    }
}
