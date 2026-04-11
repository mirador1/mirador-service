package com.example.springapi.messaging;

/**
 * Kafka reply message sent back on the {@code customer.reply} topic after enrichment
 * (Pattern 2 — request-reply).
 *
 * <p>Produced by {@link com.example.springapi.kafka.CustomerEnrichHandler} via {@code @SendTo},
 * which reads the {@code KafkaReplyHeaders.REPLY_TOPIC} and {@code CORRELATION_ID} headers
 * from the incoming {@link CustomerEnrichRequest} and routes this reply to the correct
 * {@code ReplyingKafkaTemplate} waiting thread on the sender side.
 *
 * <p>The {@code displayName} field is the computed enrichment: {@code "Name <email>"}.
 */
public record CustomerEnrichReply(Long id, String name, String email, String displayName) {
}
