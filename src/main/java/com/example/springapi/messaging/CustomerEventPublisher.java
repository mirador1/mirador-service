package com.example.springapi.messaging;

import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resilient Kafka event publisher with exponential-backoff retry.
 *
 * <p>The standard {@link KafkaTemplate#send} is fire-and-forget: the returned
 * {@code CompletableFuture} is never awaited, so broker unavailability silently
 * swallows events. This bean wraps the send in a blocking call and decorates it
 * with a Resilience4j {@code @Retry} to transparently retry transient failures
 * (broker restart, leader election, network blip) before giving up.
 *
 * <h3>Retry strategy (application.yml: resilience4j.retry.instances.kafkaPublish)</h3>
 * <pre>
 *   attempt 1 — immediate
 *   attempt 2 — 200 ms ± jitter (± 50%)
 *   attempt 3 — 400 ms ± jitter (exponential × 2)
 *   fallback   — log error and return silently (customer record is already persisted)
 * </pre>
 *
 * <h3>Separation of concerns</h3>
 * <p>This is a standalone {@code @Service} rather than a private method on
 * {@code CustomerService} because Spring AOP proxies do not intercept self-calls.
 * The {@code @Retry} annotation only activates when the method is invoked through
 * the Spring proxy, which requires a separate bean.
 *
 * [Resilience4j Retry — exponential backoff + jitter]
 */
@Service
public class CustomerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventPublisher.class);
    // Ack timeout per attempt — short enough to surface failures quickly for retry
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CustomerEventPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Publishes {@code event} to {@code topic} with {@code key} as the partition key.
     *
     * <p>The method blocks until the broker acknowledges the record (or the per-attempt
     * timeout elapses), making failures visible to the Resilience4j retry decorator.
     * Fire-and-forget callers should catch the fallback silently.
     *
     * @throws IllegalStateException if the broker cannot be reached after all retries
     *                               (propagated only when there is no {@code fallbackMethod})
     */
    @Retry(name = "kafkaPublish", fallbackMethod = "publishFallback")
    public void publish(String topic, String key, Object event) {
        try {
            kafkaTemplate.send(topic, key, event).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for Kafka ack on topic=" + topic, e);
        } catch (ExecutionException | TimeoutException e) {
            // Unwrap and rethrow so Resilience4j sees a RuntimeException and applies retry
            throw new IllegalStateException("Kafka publish failed for topic=" + topic + " key=" + key, e);
        }
    }

    /**
     * Fallback invoked after all retry attempts are exhausted.
     * Logs at ERROR level so the failure is visible in monitoring without crashing the caller.
     * The customer record was already persisted — the event loss is acceptable in this demo
     * and would normally be handled via an Outbox pattern for exactly-once semantics.
     */
    @SuppressWarnings("unused") // invoked reflectively by Resilience4j
    void publishFallback(String topic, String key, Object event, Throwable t) {
        log.error("kafka_publish_all_retries_failed topic={} key={} cause={}",
                topic, key, t.getMessage());
    }
}
