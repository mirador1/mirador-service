package com.mirador.messaging;

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
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.kafka.support.micrometer.KafkaListenerObservation;
import org.springframework.kafka.support.micrometer.KafkaRecordReceiverContext;
import org.springframework.kafka.support.micrometer.KafkaRecordSenderContext;
import org.springframework.kafka.support.micrometer.KafkaTemplateObservation;
import java.util.Map;

/**
 * Kafka infrastructure configuration: topics, producer, consumers, and request-reply template.
 *
 * <h3>Why explicit Kafka config?</h3>
 * <p>Spring Boot auto-configures a basic {@code KafkaTemplate} and {@code KafkaListenerContainerFactory},
 * but this project uses two distinct Kafka patterns that require custom beans:
 * <ul>
 *   <li><b>Pattern 1 (fire-and-forget)</b> — uses the shared {@code KafkaTemplate<String, Object>}
 *       with Jackson JSON serialization and automatic {@code __TypeId__} type headers.</li>
 *   <li><b>Pattern 2 (request-reply)</b> — requires a dedicated {@code ReplyingKafkaTemplate}
 *       and a {@code ConcurrentMessageListenerContainer} listening on the reply topic.
 *       The template adds correlation headers and blocks until the reply arrives.</li>
 * </ul>
 *
 * <h3>Serialization</h3>
 * <p>All messages are serialized with {@link org.springframework.kafka.support.serializer.JacksonJsonSerializer}
 * and {@code ADD_TYPE_INFO_HEADERS=true}, which writes a {@code __TypeId__} header containing
 * the Java class name. On the consumer side, {@code USE_TYPE_INFO_HEADERS=true} uses this
 * header to deserialize to the correct Java type without needing a static type binding.
 *
 * <p>The reply consumer uses a type-fixed deserializer ({@code false} argument means "ignore
 * type headers, always deserialize to the given class") because the reply topic only carries
 * {@link com.mirador.messaging.CustomerEnrichReply} messages.
 *
 * <h3>Topic pre-creation</h3>
 * <p>Topics are declared as {@code @Bean NewTopics} so that Spring Kafka creates them at startup
 * if they don't exist. This avoids race conditions where a producer tries to send to a
 * topic that hasn't been created yet by the broker.
 *
 * <p>{@code @EnableKafka} activates the {@code @KafkaListener} annotation processing.
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    // Sonar java:S1192 — "peer.service" and "kafka" are repeated in all four observation
    // customisers below. Centralised here to avoid the duplicate-literal CRITICAL.
    private static final String PEER_SERVICE_KEY   = "peer.service";
    private static final String PEER_SERVICE_KAFKA = "kafka";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ─── Topics (pre-created at startup to avoid race conditions) ────────────

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

    // ─── Producer ────────────────────────────────────────────────────────────

    @Bean
    ProducerFactory<String, Object> producerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class,
                JacksonJsonSerializer.ADD_TYPE_INFO_HEADERS, true
        ));
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> pf,
                                                 ObservationRegistry observationRegistry) {
        var template = new KafkaTemplate<>(pf);
        // spring.kafka.template.observation-enabled only applies to Spring Boot's auto-configured
        // KafkaTemplate bean. For manually declared beans, observation + registry must be set explicitly.
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        // Add peer.service=kafka so Tempo renders Kafka as a named external node in the service map
        template.setObservationConvention(new KafkaTemplateObservation.DefaultKafkaTemplateObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(KafkaRecordSenderContext ctx) {
                return super.getLowCardinalityKeyValues(ctx).and(KeyValue.of(PEER_SERVICE_KEY, PEER_SERVICE_KAFKA));
            }
        });
        return template;
    }

    // ─── @KafkaListener container factory (shared by all listeners) ──────────

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate,
            ObservationRegistry observationRegistry) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(listenerConsumerFactory());
        // required for @SendTo (reply routing in Pattern 2)
        factory.setReplyTemplate(kafkaTemplate);
        // spring.kafka.listener.observation-enabled only applies to auto-configured factories.
        // Must be set programmatically on manually declared beans.
        factory.getContainerProperties().setObservationEnabled(true);
        factory.getContainerProperties().setObservationRegistry(observationRegistry);
        // Add peer.service=kafka so Tempo renders Kafka as a named external node in the service map
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
                // "earliest": on first start (no committed offset) replay all messages from the beginning.
                // Ensures no CustomerCreatedEvent is lost if the consumer restarts before committing.
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class,
                // Resolve target Java type from the __TypeId__ header written by JacksonJsonSerializer.
                // Allows the same factory to deserialize both CustomerCreatedEvent and other event types.
                JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, "true",
                // Only trust our own messaging package — prevents deserialization of arbitrary classes
                // from untrusted producers (deserialization gadget attack mitigation).
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "com.mirador.messaging"
        ));
    }

    // ─── Pattern 2: ReplyingKafkaTemplate (synchronous request-reply) ─────────

    @Bean
    @SuppressWarnings("unchecked")
    ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate(
            ProducerFactory<String, Object> pf,
            ConcurrentMessageListenerContainer<String, CustomerEnrichReply> replyListenerContainer,
            ObservationRegistry observationRegistry) {
        // Safe cast: the JacksonJsonSerializer handles any Object type at runtime
        var template = new ReplyingKafkaTemplate<>(
                (ProducerFactory<String, CustomerEnrichRequest>) (ProducerFactory<?, ?>) pf,
                replyListenerContainer
        );
        // Enable Micrometer observation — generates Kafka producer spans with
        // messaging.system=kafka and messaging.destination.name=customer.request
        template.setObservationEnabled(true);
        template.setObservationRegistry(observationRegistry);
        // Add peer.service=kafka so Tempo renders Kafka as a named external node in the service map
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
        // Enable observation on the reply consumer — generates Kafka consumer spans
        props.setObservationEnabled(true);
        // Add peer.service=kafka so Tempo renders Kafka as a named external node in the service map
        props.setObservationConvention(new KafkaListenerObservation.DefaultKafkaListenerObservationConvention() {
            @Override
            public KeyValues getLowCardinalityKeyValues(KafkaRecordReceiverContext ctx) {
                return super.getLowCardinalityKeyValues(ctx).and(KeyValue.of(PEER_SERVICE_KEY, PEER_SERVICE_KAFKA));
            }
        });
        return new ConcurrentMessageListenerContainer<>(replyConsumerFactory(), props);
    }

    private DefaultKafkaConsumerFactory<String, CustomerEnrichReply> replyConsumerFactory() {
        // false = always deserialize to CustomerEnrichReply, ignoring __TypeId__ headers.
        // The reply topic carries only one message type so dynamic type resolution is unnecessary.
        var deser = new JacksonJsonDeserializer<>(CustomerEnrichReply.class, false);
        deser.addTrustedPackages("com.mirador.messaging");
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                // "latest": skip any replies that arrived before this consumer started.
                // Stale replies (from a previous request-reply cycle) would be matched to the wrong
                // correlation ID by ReplyingKafkaTemplate and silently discarded anyway, but
                // "latest" avoids wasting time deserializing them.
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"
        ), new StringDeserializer(), deser);
    }
}
