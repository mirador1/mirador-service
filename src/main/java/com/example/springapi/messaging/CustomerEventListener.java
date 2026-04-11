package com.example.springapi.kafka;

import com.example.springapi.event.CustomerCreatedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka listener that consumes customer-created events (Pattern 1 — fire-and-forget).
 *
 * <p>This component is the <em>consumer side</em> of the async event pattern:
 * <ol>
 *   <li>{@code POST /customers} persists the customer and immediately publishes a
 *       {@link CustomerCreatedEvent} to the {@code customer.created} topic via
 *       {@code KafkaTemplate} — without waiting for consumption.</li>
 *   <li>This listener picks up the event asynchronously and processes it independently
 *       of the HTTP request lifecycle.</li>
 * </ol>
 *
 * <p>In this demo the "processing" is just a structured log entry and a metric increment.
 * In a real system this would trigger downstream workflows: sending a welcome email, updating
 * a read model, notifying another service, etc.
 *
 * <p>Pattern benefits:
 * <ul>
 *   <li><b>Decoupling</b> — the HTTP handler doesn't know about downstream consumers.</li>
 *   <li><b>Latency</b> — the HTTP response is unaffected by consumer processing time.</li>
 *   <li><b>Resilience</b> — if the consumer is down, Kafka retains the event until it recovers.</li>
 * </ul>
 *
 * <p>A Micrometer counter ({@code kafka.customer.created.processed}) tracks throughput,
 * visible via {@code /actuator/metrics/kafka.customer.created.processed}.
 */
@Component
public class CustomerEventListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventListener.class);

    private final Counter kafkaEventProcessedCounter;

    public CustomerEventListener(MeterRegistry meterRegistry) {
        this.kafkaEventProcessedCounter = Counter.builder("kafka.customer.created.processed")
                .description("Number of CustomerCreatedEvents processed from Kafka")
                .register(meterRegistry);
    }

    /** Handles a customer-created event: logs it and increments the event counter. */
    @KafkaListener(
            topics = "${app.kafka.topics.customer-created}",
            groupId = "${app.kafka.consumer-group-id}"
    )
    public void onCustomerCreated(CustomerCreatedEvent event) {
        log.info("kafka_event type=CustomerCreatedEvent id={} name={}", event.id(), event.name());
        kafkaEventProcessedCounter.increment();
    }
}
