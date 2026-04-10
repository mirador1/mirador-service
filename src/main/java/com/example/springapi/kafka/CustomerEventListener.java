package com.example.springapi.kafka;

import com.example.springapi.event.CustomerCreatedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class CustomerEventListener {

    private static final Logger log = LoggerFactory.getLogger(CustomerEventListener.class);

    private final Counter kafkaEventProcessedCounter;

    public CustomerEventListener(MeterRegistry meterRegistry) {
        this.kafkaEventProcessedCounter = Counter.builder("kafka.customer.created.processed")
                .description("Number of CustomerCreatedEvents processed from Kafka")
                .register(meterRegistry);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.customer-created}",
            groupId = "${app.kafka.consumer-group-id}"
    )
    public void onCustomerCreated(CustomerCreatedEvent event) {
        log.info("kafka_event type=CustomerCreatedEvent id={} name={}", event.id(), event.name());
        kafkaEventProcessedCounter.increment();
    }
}
