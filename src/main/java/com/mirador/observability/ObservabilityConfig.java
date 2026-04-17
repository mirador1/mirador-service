package com.mirador.observability;

import com.mirador.customer.RecentCustomerBuffer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers custom Micrometer metrics that are not emitted by auto-configuration.
 *
 * <h3>Why a separate config class?</h3>
 * <p>Spring Boot auto-configures many metrics automatically (JVM memory, HTTP server,
 * datasource pool, Kafka producer/consumer, etc.). Custom business metrics that don't map
 * directly to a Spring-managed component need to be registered manually — this class is the
 * canonical place to do that.
 *
 * <h3>Gauge vs Counter vs Timer</h3>
 * <ul>
 *   <li><b>Gauge</b> — a value that goes up <em>and</em> down (buffer size, queue depth,
 *       cache hit rate). Prometheus scrapes the current value at each interval.</li>
 *   <li><b>Counter</b> — monotonically increasing count (events processed, errors). Used for
 *       rate calculations: {@code rate(counter[1m])} in PromQL.</li>
 *   <li><b>Timer</b> — records duration and count simultaneously. With
 *       {@code publishPercentileHistogram()} enabled, Prometheus can compute p50/p95/p99.</li>
 * </ul>
 *
 * <p>The {@code customer.recent.buffer.size} gauge published here appears in Prometheus as
 * {@code customer_recent_buffer_size} and can be visualised in the Grafana dashboard to
 * show how the in-memory buffer fills up over time.
 */
@Configuration
public class ObservabilityConfig {

    /**
     * Enables {@code @Observed} annotation support on Spring beans.
     *
     * <p>{@link ObservedAspect} intercepts methods annotated with {@code @Observed} and creates
     * Micrometer Observation spans around them. These spans are exported via the Micrometer/OTel
     * bridge to Tempo as distributed traces, so each CustomerService call appears as a named span
     * in Grafana dashboards alongside the HTTP and DB spans.
     *
     * <p>Spring Boot 3.x auto-configures {@link ObservationRegistry} but does NOT auto-register
     * this aspect — it must be declared as a bean explicitly.
     */
    @Bean
    ObservedAspect observedAspect(ObservationRegistry observationRegistry) {
        return new ObservedAspect(observationRegistry);
    }

    /**
     * Registers a Gauge that reports the current number of entries in the recent-customer buffer.
     *
     * <p>The Gauge calls {@code buffer.getRecent().size()} on every Prometheus scrape.
     * The try/catch ensures the gauge never propagates exceptions to the metrics registry,
     * which would cause the scrape to fail entirely.
     */
    @Bean
    Gauge recentCustomerBufferGauge(MeterRegistry registry, RecentCustomerBuffer recentCustomerBuffer) {
        return Gauge.builder("customer.recent.buffer.size", recentCustomerBuffer, buffer -> {
                    try {
                        // Redis LLEN is O(1) — cheaper than fetching all entries and counting
                        return (double) buffer.size();
                    } catch (Exception _) {
                        return 0;
                    }
                })
                .description("Current size of the recent customer in-memory buffer")
                .register(registry);
    }
}
