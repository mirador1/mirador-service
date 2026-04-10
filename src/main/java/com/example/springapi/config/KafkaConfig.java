package com.example.springapi.config;

import com.example.springapi.event.CustomerEnrichReply;
import com.example.springapi.event.CustomerEnrichRequest;
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

import java.util.Map;

@Configuration
@EnableKafka
public class KafkaConfig {

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
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                JsonSerializer.ADD_TYPE_INFO_HEADERS, true
        ));
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> pf) {
        return new KafkaTemplate<>(pf);
    }

    // ─── @KafkaListener container factory (shared by all listeners) ──────────

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            KafkaTemplate<String, Object> kafkaTemplate) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(listenerConsumerFactory());
        // required for @SendTo (reply routing in Pattern 2)
        factory.setReplyTemplate(kafkaTemplate);
        return factory;
    }

    private DefaultKafkaConsumerFactory<String, Object> listenerConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
                // resolve target type from the __TypeId__ header set by the producer
                JsonDeserializer.USE_TYPE_INFO_HEADERS, "true",
                JsonDeserializer.TRUSTED_PACKAGES, "com.example.springapi.event"
        ));
    }

    // ─── Pattern 2: ReplyingKafkaTemplate (synchronous request-reply) ─────────

    @Bean
    @SuppressWarnings("unchecked")
    ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate(
            ProducerFactory<String, Object> pf,
            ConcurrentMessageListenerContainer<String, CustomerEnrichReply> replyListenerContainer) {
        // Safe cast: the JsonSerializer handles any Object type at runtime
        return new ReplyingKafkaTemplate<>(
                (ProducerFactory<String, CustomerEnrichRequest>) (ProducerFactory<?, ?>) pf,
                replyListenerContainer
        );
    }

    @Bean
    ConcurrentMessageListenerContainer<String, CustomerEnrichReply> replyListenerContainer(
            @Value("${app.kafka.topics.customer-reply}") String replyTopic,
            @Value("${app.kafka.reply-group-id}") String replyGroupId) {
        var props = new ContainerProperties(replyTopic);
        props.setGroupId(replyGroupId);
        return new ConcurrentMessageListenerContainer<>(replyConsumerFactory(), props);
    }

    private DefaultKafkaConsumerFactory<String, CustomerEnrichReply> replyConsumerFactory() {
        // false = always deserialize to CustomerEnrichReply, ignore type headers
        var deser = new JsonDeserializer<>(CustomerEnrichReply.class, false);
        deser.addTrustedPackages("com.example.springapi.event");
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"
        ), new StringDeserializer(), deser);
    }
}
