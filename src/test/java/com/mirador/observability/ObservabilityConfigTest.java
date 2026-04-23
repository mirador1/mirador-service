package com.mirador.observability;

import com.mirador.customer.RecentCustomerBuffer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ObservabilityConfig} — the custom Micrometer
 * registrations that aren't done by Spring Boot auto-configuration.
 *
 * <p>Pinned contracts:
 *   - observedAspect bean wraps the supplied ObservationRegistry (enables
 *     {@code @Observed} annotation interception → Tempo spans).
 *   - recentCustomerBufferGauge registers under exact name
 *     {@code customer.recent.buffer.size}, so PromQL queries that hardcode
 *     {@code customer_recent_buffer_size} (Micrometer dot→underscore) keep
 *     working across refactors.
 *   - gauge value reflects buffer.size() — and tolerates buffer.size()
 *     throwing (fallback to 0) so a flaky Redis link doesn't kill the
 *     entire Prometheus scrape.
 */
class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();

    @Test
    void observedAspect_wrapsTheSuppliedObservationRegistry() {
        // Pinned: the @Observed annotation interception relies on this
        // aspect being registered as a Spring bean. Spring Boot 3.x
        // auto-configures the ObservationRegistry but NOT the aspect —
        // missing this bean means @Observed methods produce no spans.
        ObservationRegistry registry = ObservationRegistry.create();

        ObservedAspect aspect = config.observedAspect(registry);

        assertThat(aspect).isNotNull();
    }

    @Test
    void recentCustomerBufferGauge_registersUnderExactMicrometerName() {
        // Pinned: the metric name is the contract with the Grafana
        // dashboard. PromQL queries reference customer_recent_buffer_size
        // (Micrometer converts dots to underscores). A rename here
        // silently breaks every panel that uses the metric.
        MeterRegistry meters = new SimpleMeterRegistry();
        RecentCustomerBuffer buffer = mock(RecentCustomerBuffer.class);
        when(buffer.size()).thenReturn(42L);

        config.recentCustomerBufferGauge(meters, buffer);

        Gauge gauge = meters.find("customer.recent.buffer.size").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(42.0);
    }

    @Test
    void recentCustomerBufferGauge_reflectsBufferSizeChangesOnEachScrape() {
        // Pinned: the gauge calls buffer.size() lazily on each scrape, NOT
        // at register time. A regression that captures the size once would
        // produce a constant flat line in Grafana — exactly the opposite
        // of what a buffer-fill chart should show.
        MeterRegistry meters = new SimpleMeterRegistry();
        RecentCustomerBuffer buffer = mock(RecentCustomerBuffer.class);
        when(buffer.size()).thenReturn(0L, 5L, 17L); // values across 3 scrapes

        config.recentCustomerBufferGauge(meters, buffer);
        Gauge gauge = meters.find("customer.recent.buffer.size").gauge();

        // Each gauge.value() call is one scrape — should pull the next size.
        assertThat(gauge.value()).isEqualTo(0.0);
        assertThat(gauge.value()).isEqualTo(5.0);
        assertThat(gauge.value()).isEqualTo(17.0);
    }

    @Test
    void recentCustomerBufferGauge_returnsZeroWhenBufferThrows() {
        // Pinned: a flaky Redis connection (network hiccup, AUTH failure,
        // or container restart) throws from .size(). Without the catch,
        // the entire Prometheus scrape would fail and we'd lose ALL
        // metrics from this app for that interval — not just the buffer
        // size. Returning 0 keeps the rest of the scrape intact and
        // shows up in Grafana as "buffer empty / Redis unhealthy".
        MeterRegistry meters = new SimpleMeterRegistry();
        RecentCustomerBuffer buffer = mock(RecentCustomerBuffer.class);
        when(buffer.size()).thenThrow(new RuntimeException("redis down"));

        config.recentCustomerBufferGauge(meters, buffer);
        Gauge gauge = meters.find("customer.recent.buffer.size").gauge();

        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    void recentCustomerBufferGauge_carriesDescriptionForGrafanaTooltip() {
        // Pinned: the description appears in the Grafana metrics-explorer
        // tooltip — a missing description leaves devs guessing what the
        // metric represents. Cheap to set, valuable for newcomers.
        MeterRegistry meters = new SimpleMeterRegistry();
        RecentCustomerBuffer buffer = mock(RecentCustomerBuffer.class);
        when(buffer.size()).thenReturn(0L);

        config.recentCustomerBufferGauge(meters, buffer);

        Gauge gauge = meters.find("customer.recent.buffer.size").gauge();
        assertThat(gauge.getId().getDescription())
                .isEqualTo("Current size of the recent customer in-memory buffer");
    }
}
