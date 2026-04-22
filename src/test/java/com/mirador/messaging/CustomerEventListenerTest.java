package com.mirador.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CustomerEventListener} — the consumer side of the
 * fire-and-forget async event pattern. Same structure as
 * {@link CustomerEnrichHandlerTest} (no Kafka broker, just call the
 * listener method directly).
 */
class CustomerEventListenerTest {

    private MeterRegistry meterRegistry;
    private CustomerEventListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = new CustomerEventListener(meterRegistry);
    }

    @Test
    void onCustomerCreated_incrementsCounter() {
        var counter = meterRegistry.find("kafka.customer.created.processed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isZero();

        listener.onCustomerCreated(new CustomerCreatedEvent(1L, "Alice", "alice@example.com"));

        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void onCustomerCreated_handlesMultipleEventsInOrder() {
        // Each event independently increments — guards against any "skip
        // duplicate" or "batch" optimisation that would lose count fidelity
        // (the metric is the SLO baseline for the async pipeline).
        for (long i = 1; i <= 100; i++) {
            listener.onCustomerCreated(new CustomerCreatedEvent(i, "User-" + i, i + "@x"));
        }

        var counter = meterRegistry.find("kafka.customer.created.processed").counter();
        assertThat(counter.count()).isEqualTo(100.0);
    }

    @Test
    void onCustomerCreated_doesNotThrowOnNullishLookingFields() {
        // The listener does no validation — it just logs + counts. Even with
        // an event that has a nominal null email (rare but possible if a
        // producer drops it), the listener must not throw and break the
        // consumer group.
        listener.onCustomerCreated(new CustomerCreatedEvent(0L, "", ""));

        var counter = meterRegistry.find("kafka.customer.created.processed").counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
