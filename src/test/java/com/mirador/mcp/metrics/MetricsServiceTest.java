package com.mirador.mcp.metrics;

import com.mirador.mcp.dto.MetricSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetricsService}.
 *
 * <p>Uses a {@link SimpleMeterRegistry} (in-memory, no Prometheus / Mimir)
 * to drive a real Micrometer pipeline. Tests cover the regex + tag filter,
 * primary-value extraction per meter type, and the result cap.
 */
class MetricsServiceTest {

    private SimpleMeterRegistry registry;
    private MetricsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new MetricsService(registry);
    }

    @Test
    void counterPrimaryValueIsCount() {
        Counter c = registry.counter("orders.created", "status", "PENDING");
        c.increment();
        c.increment();

        List<MetricSnapshot> result = service.getMetrics("orders\\.created", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("COUNTER");
        assertThat(result.get(0).value()).isEqualTo(2.0);
        assertThat(result.get(0).tags()).containsEntry("status", "PENDING");
    }

    @Test
    void gaugePrimaryValueIsValue() {
        AtomicInteger ref = new AtomicInteger(42);
        Gauge.builder("custom.queue.size", ref, AtomicInteger::doubleValue).register(registry);

        List<MetricSnapshot> result = service.getMetrics("custom\\.queue\\.size", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("GAUGE");
        assertThat(result.get(0).value()).isEqualTo(42.0);
    }

    @Test
    void timerPrimaryValueIsMeanInSeconds() {
        Timer timer = registry.timer("http.server.requests");
        timer.record(java.time.Duration.ofMillis(100));
        timer.record(java.time.Duration.ofMillis(200));

        List<MetricSnapshot> result = service.getMetrics("http\\.server\\.requests", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo("TIMER");
        // Mean of 100+200ms = 150ms = 0.15s. Allow tolerance for clock granularity.
        assertThat(result.get(0).value()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(0.05));
    }

    @Test
    void emptyNameFilterMatchesEverything() {
        registry.counter("a");
        registry.counter("b");
        registry.counter("c");

        List<MetricSnapshot> result = service.getMetrics(null, null);
        assertThat(result).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void invalidRegexFallsBackToMatchAllRatherThanThrow() {
        registry.counter("alpha");
        // "[invalid" is a bad regex — service must not blow up.
        List<MetricSnapshot> result = service.getMetrics("[invalid", null);
        assertThat(result).isNotEmpty();
    }

    @Test
    void tagFilterRequiresAllTagsToMatchExactly() {
        registry.counter("orders", Tags.of("status", "PENDING", "channel", "web"));
        registry.counter("orders", Tags.of("status", "SHIPPED", "channel", "web"));

        // Filter on status=PENDING — should match exactly one meter.
        List<MetricSnapshot> result = service.getMetrics("^orders$", Map.of("status", "PENDING"));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).tags()).containsEntry("status", "PENDING");
    }

    @Test
    void tagFilterMissingTagExcludesMeter() {
        registry.counter("orders", Tags.of("status", "PENDING"));

        List<MetricSnapshot> result = service.getMetrics("^orders$", Map.of("missing", "value"));
        assertThat(result).isEmpty();
    }

    @Test
    void resultCapAppliesAcrossManyMeters() {
        // Register 220 meters under the same name regex so the cap kicks in.
        for (int i = 0; i < 220; i++) {
            registry.counter("test.cap." + i);
        }
        List<MetricSnapshot> result = service.getMetrics("test\\.cap\\..*", null);
        assertThat(result).hasSize(MetricsService.MAX_RESULTS);
    }
}
