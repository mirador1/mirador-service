package com.mirador.messaging;

// SB3 overlay — Spring Kafka 4.x prefixed its V3-aware serializer classes with
// "Jackson" (JacksonJsonSerializer / JacksonJsonDeserializer) and made them
// the recommended path. Internally these go through tools.jackson.* (V3), and
// JsonKafkaHeaderMapper in SK 4.x also hard-references V3 inside its constructor —
// so even instantiating a plain `new KafkaTemplate(pf)` blows up with
// NoClassDefFoundError on tools.jackson.databind.json.JsonMapper$Builder when
// only Jackson V2 is on the classpath (SB3's case).
//
// SB3 pins Spring Kafka to 3.3.4 (see pom.xml SB3 profile dependencyManagement)
// which uses Jackson V2 throughout AND only exposes the legacy
// JsonSerializer / JsonDeserializer names (no "Jackson" prefix). This overlay
// swaps the imports + class references to match what SK 3.3.4 ships :
//   - JacksonJsonSerializer   → JsonSerializer
//   - JacksonJsonDeserializer → JsonDeserializer
// All static field constants (ADD_TYPE_INFO_HEADERS, USE_TYPE_INFO_HEADERS,
// TRUSTED_PACKAGES) exist on both classes with the same names + types.
//
// Created 2026-04-25 wave 8 (svc 1.0.57) per ADR-0061 Entry 4 + ADR-0060
// (SB3 prod-grade target). Main code keeps the SK 4.x JacksonJson* classes
// as the canonical default-target API.
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.kafka.support.micrometer.KafkaListenerObservation;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext;
import org.springframework.kafka.support.micrometer.KafkaTemplateObservation;
import java.util.Map;

/**
 * SB3 overlay variant of {@link com.mirador.messaging.KafkaConfig}.
 *
 * <p>Same semantics as the main file ; only the Spring Kafka serializer class
 * names differ (V2 legacy {@code JsonSerializer}/{@code JsonDeserializer} vs
 * the SK 4.x V3-aware {@code JacksonJsonSerializer}/{@code JacksonJsonDeserializer}).
 * The static field constants ({@code ADD_TYPE_INFO_HEADERS},
 * {@code USE_TYPE_INFO_HEADERS}, {@code TRUSTED_PACKAGES}) exist on both
 * classes with identical names and types.
 *
 * <p>See main {@code src/main/java/com/mirador/messaging/KafkaConfig.java}
 * for the full Javadoc — kept short here to minimise overlay drift.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private static final String PEER_SERVICE_KEY   = "peer.service";
    private static final String PEER_SERVICE_KAFKA = "kafka";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    NewTopics kafkaTopics(
            @Value("${app.kafka.topics.customer-created}") String created,
            @Value("${app.kafka.topics.customer-request}") String request,
            @Value("${app.kafka.topics.customer-reply}") String reply) {
        return new NewTopics(
                TopicBuilder.name(created).build(),
                TopicBuilder.name(request).build(),
                TopicBuilder.name(reply).build()
        );
    }

    @Bean
    ProducerFactory<String, Object> producerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, true
        ));
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> pf,
                                                 ObservationRegistry observationRegistry) {
        var template = new KafkaTemplate<>(pf);
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        template.setObservationConvention(new KafkaTemplateObservation.DefaultKafkaTemplateObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(KafkaRecordSenderContext ctx) {
                return super.getLowCardinalityKeyValues(ctx).and(KeyValue.of(PEER_SERVICE_KEY, PEER_SERVICE_KAFKA));
            }
        });
        return template;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate,
            ObservationRegistry observationRegistry) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(listenerConsumerFactory());
        factory.setReplyTemplate(kafkaTemplate);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setObservationRegistry(observationRegistry);
        factory.getContainerProperties().setObservationConvention(
                new KafkaListenerObservation.DefaultKafkaListenerObservationConvention() {
                    @Override
                    public KeyValues getLowCardinalityKeyValues(KafkaRecordReceiverContext ctx) {
                        return super.getLowCardinalityKeyValues(ctx).and(KeyValue.of(PEER_SERVICE_KEY, PEER_SERVICE_KAFKA));
                    }
                });
        return factory;
    }

    private DefaultKafkaConsumerFactory<String, Object> listenerConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
                JsonDeserializer.USE_TYPE_INFO_HEADERS, "true",
                JsonDeserializer.TRUSTED_PACKAGES, "com.mirador.messaging"
        ));
    }

    @Bean
    @SuppressWarnings("unchecked")
    ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate(
            ProducerFactory<String, Object> pf,
            ConcurrentMessageListenerContainer<String, CustomerEnrichReply> replyListenerContainer,
            ObservationRegistry observationRegistry) {
        var template = new ReplyingKafkaTemplate<>(
                (ProducerFactory<String, CustomerEnrichRequest>) (ProducerFactory<?, ?>) pf,
                replyListenerContainer
        );
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        template.setObservationConvention(new KafkaTemplateObservation.DefaultKafkaTemplateObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(KafkaRecordSenderContext ctx) {
                return super.getLowCardinalityKeyValues(ctx).and(KeyValue.of(PEER_SERVICE_KEY, PEER_SERVICE_KAFKA));
            }
        });
        return template;
    }

    @Bean
    ConcurrentMessageListenerContainer<String, CustomerEnrichReply> replyListenerContainer(
            @Value("${app.kafka.topics.customer-reply}") String replyTopic,
            @Value("${app.kafka.reply-group-id}") String replyGroupId) {
        var props = new ContainerProperties(replyTopic);
        props.setGroupId(replyGroupId);
        props.setObservationEnabled(true);
        props.setObservationConvention(new KafkaListenerObservation.DefaultKafkaListenerObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(KafkaRecordReceiverContext ctx) {
                return super.getLowCardinalityKeyValues(ctx).and(KeyValue.of(PEER_SERVICE_KEY, PEER_SERVICE_KAFKA));
            }
        });
        return new ConcurrentMessageListenerContainer<>(replyConsumerFactory(), props);
    }

    private DefaultKafkaConsumerFactory<String, CustomerEnrichReply> replyConsumerFactory() {
        var deser = new JsonDeserializer<>(CustomerEnrichReply.class, false);
        deser.addTrustedPackages("com.mirador.messaging");
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"
        ), new StringDeserializer(), deser);
    }
}
