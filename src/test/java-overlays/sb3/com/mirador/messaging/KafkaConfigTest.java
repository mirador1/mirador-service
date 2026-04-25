package com.mirador.messaging;

// SB3 test overlay — mirrors src/main/java-overlays/sb3/com/mirador/messaging/KafkaConfig.java :
// uses Spring Kafka 3.3.4 V2 legacy class names (JsonSerializer / JsonDeserializer)
// instead of the SK 4.x JacksonJson* prefixed variants. The test asserts on the
// .class literals declared in the producer / consumer config maps, so the test
// types must match the overlay's serializer types — otherwise the assertion
// `containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class)`
// would fail because the overlay actually wires JsonSerializer.class.
//
// All other assertions (broker, key serializer, type-info / trusted-packages,
// observation enabled, latest/earliest offset reset, group-id) are identical
// to the main test — they only depend on configuration map entries that have
// the same string keys + boolean values in both V2 and V3 paths.
//
// Created 2026-04-25 wave 8 (svc 1.0.57) per ADR-0061 Entry 4.
// Main test at src/test/java/com/mirador/messaging/KafkaConfigTest.java is
// excluded from SB3 merged-sources by the antrun copy-then-overwrite step
// (the overlay overwrites it after the base copy).
import io.micrometer.observation.ObservationRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConfigTest {

    private static final String BROKERS = "localhost:9092";

    private KafkaConfig newConfig() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", BROKERS);
        return config;
    }

    @Test
    void kafkaTopics_buildsThreeTopicsWithSuppliedNames() {
        NewTopics topics = newConfig().kafkaTopics(
                "customer.created",
                "customer.request",
                "customer.reply"
        );

        @SuppressWarnings("unchecked")
        var collection = (java.util.Collection<org.apache.kafka.clients.admin.NewTopic>)
                ReflectionTestUtils.invokeMethod(topics, "getNewTopics");
        assertThat(collection)
                .extracting(t -> t.name())
                .containsExactlyInAnyOrder("customer.created", "customer.request", "customer.reply");
    }

    @Test
    void producerFactory_usesStringKeyAndJacksonJsonValue() {
        ProducerFactory<String, Object> pf = newConfig().producerFactory();

        DefaultKafkaProducerFactory<?, ?> dpf = (DefaultKafkaProducerFactory<?, ?>) pf;
        var configs = dpf.getConfigurationProperties();

        assertThat(configs).containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKERS);
        assertThat(configs).containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // SB3 overlay : V2 legacy JsonSerializer instead of SK 4.x JacksonJsonSerializer.
        assertThat(configs).containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        assertThat(configs).containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, true);
    }

    @Test
    void kafkaTemplate_hasObservationEnabledForTempoSpans() {
        ProducerFactory<String, Object> pf = newConfig().producerFactory();

        var template = newConfig().kafkaTemplate(pf, ObservationRegistry.create());

        Boolean enabled = (Boolean) ReflectionTestUtils.getField(template, "observationEnabled");
        assertThat(enabled).isTrue();
    }

    @Test
    void listenerConsumerFactory_usesEarliestOffsetResetAndTrustedPackages() {
        var factory = newConfig().kafkaListenerContainerFactory(
                newConfig().kafkaTemplate(newConfig().producerFactory(), ObservationRegistry.create()),
                ObservationRegistry.create()
        );

        DefaultKafkaConsumerFactory<?, ?> consumerFactory = (DefaultKafkaConsumerFactory<?, ?>) factory.getConsumerFactory();
        var configs = consumerFactory.getConfigurationProperties();

        assertThat(configs).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(configs).containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // SB3 overlay : V2 legacy JsonDeserializer instead of SK 4.x JacksonJsonDeserializer.
        assertThat(configs).containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        assertThat(configs).containsEntry(JsonDeserializer.USE_TYPE_INFO_HEADERS, "true");
        assertThat(configs).containsEntry(JsonDeserializer.TRUSTED_PACKAGES, "com.mirador.messaging");
    }

    @Test
    void kafkaListenerContainerFactory_hasObservationEnabledOnContainerProperties() {
        var factory = newConfig().kafkaListenerContainerFactory(
                newConfig().kafkaTemplate(newConfig().producerFactory(), ObservationRegistry.create()),
                ObservationRegistry.create()
        );

        assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();
    }

    @Test
    void replyListenerContainer_usesLatestOffsetReset() {
        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", BROKERS);

        var container = config.replyListenerContainer("customer.reply", "reply-group");

        DefaultKafkaConsumerFactory<?, ?> consumerFactory =
                (DefaultKafkaConsumerFactory<?, ?>) ReflectionTestUtils.getField(container, "consumerFactory");
        var configs = consumerFactory.getConfigurationProperties();

        assertThat(configs).containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        assertThat(configs).containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKERS);
        assertThat(container.getContainerProperties().getGroupId()).isEqualTo("reply-group");
    }
}
