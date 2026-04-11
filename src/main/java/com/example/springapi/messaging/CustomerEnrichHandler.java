package com.example.springapi.messaging;

import com.example.springapi.messaging.CustomerEnrichReply;
import com.example.springapi.messaging.CustomerEnrichRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;

/**
 * Kafka listener that handles customer enrichment requests (Pattern 2 — synchronous request-reply).
 *
 * <p>This component is the <em>server side</em> of the Kafka request-reply pattern:
 * <ol>
 *   <li>Listens on the {@code customer.request} topic (configured via
 *       {@code app.kafka.topics.customer-request}).</li>
 *   <li>Computes a {@code displayName} by combining the customer's name and email.</li>
 *   <li>Returns a {@link CustomerEnrichReply} which Spring Kafka automatically publishes
 *       to the reply topic specified in the {@code KafkaReplyHeaders.REPLY_TOPIC} header.
 *       That header is set by {@code ReplyingKafkaTemplate} on the caller side — the handler
 *       does not need to know the reply topic name.</li>
 * </ol>
 *
 * <p>{@code @SendTo} with no argument means "reply to the topic from the message header".
 * This is the key mechanism that makes the round-trip transparent: the caller sets the
 * reply address, the handler just returns the value.
 *
 * <p>The consumer group uses a dedicated suffix ({@code -enrich}) to avoid offset
 * interference with the async event listener that also consumes from other topics.
 *
 * <p>A Micrometer counter ({@code kafka.customer.enrich.handled}) tracks the number of
 * requests processed, visible in {@code /actuator/metrics/kafka.customer.enrich.handled}.
 */
@Component
public class CustomerEnrichHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerEnrichHandler.class);

    private final Counter enrichRequestCounter;

    public CustomerEnrichHandler(MeterRegistry meterRegistry) {
        this.enrichRequestCounter = Counter.builder("kafka.customer.enrich.handled")
                .description("Number of CustomerEnrichRequests handled from Kafka")
                .register(meterRegistry);
    }

    /**
     * Processes an enrichment request and returns the enriched customer data.
     *
     * <p>{@code @SendTo} (no argument) instructs Spring Kafka to publish the return value
     * to the topic specified in the {@code KafkaReplyHeaders.REPLY_TOPIC} message header,
     * which is set automatically by the {@code ReplyingKafkaTemplate} on the sending side.
     */
    @KafkaListener(
            topics = "${app.kafka.topics.customer-request}",
            groupId = "${app.kafka.consumer-group-id}-enrich"
    )
    @SendTo // reply destination is driven by the KafkaReplyHeaders.REPLY_TOPIC header, not hardcoded here
    public CustomerEnrichReply handleEnrichRequest(CustomerEnrichRequest request) {
        log.info("kafka_enrich id={} name={}", request.id(), request.name());
        enrichRequestCounter.increment();
        // Business logic: assemble a display name in "Name <email>" format
        String displayName = request.name() + " <" + request.email() + ">";
        return new CustomerEnrichReply(request.id(), request.name(), request.email(), displayName);
    }
}
