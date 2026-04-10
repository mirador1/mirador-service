package com.example.springapi.kafka;

import com.example.springapi.event.CustomerEnrichReply;
import com.example.springapi.event.CustomerEnrichRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Component;

@Component
public class CustomerEnrichHandler {

    private static final Logger log = LoggerFactory.getLogger(CustomerEnrichHandler.class);

    private final Counter enrichRequestCounter;

    public CustomerEnrichHandler(MeterRegistry meterRegistry) {
        this.enrichRequestCounter = Counter.builder("kafka.customer.enrich.handled")
                .description("Number of CustomerEnrichRequests handled from Kafka")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.customer-request}",
            groupId = "${app.kafka.consumer-group-id}-enrich"
    )
    @SendTo // sends reply to the topic from KafkaReplyHeaders.REPLY_TOPIC header (set by ReplyingKafkaTemplate)
    public CustomerEnrichReply handleEnrichRequest(CustomerEnrichRequest request) {
        log.info("kafka_enrich id={} name={}", request.id(), request.name());
        enrichRequestCounter.increment();
        String displayName = request.name() + " <" + request.email() + ">";
        return new CustomerEnrichReply(request.id(), request.name(), request.email(), displayName);
    }
}
