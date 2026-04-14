package com.mirador.messaging;

/**
 * Kafka message sent to the {@code customer.request} topic to initiate a synchronous enrich
 * round-trip (Pattern 2 — request-reply).
 *
 * <p>Published by {@link com.mirador.controller.CustomerController#enrich} via
 * {@code ReplyingKafkaTemplate}. The template automatically appends two headers:
 * <ul>
 *   <li>{@code KafkaReplyHeaders.REPLY_TOPIC} — the topic where the reply should be sent.</li>
 *   <li>{@code KafkaReplyHeaders.CORRELATION_ID} — a UUID correlating this request to its reply.</li>
 * </ul>
 *
 * <p>Consumed by {@link com.mirador.kafka.CustomerEnrichHandler}, which uses the
 * headers to route the reply back to the correct waiting thread.
 */
public record CustomerEnrichRequest(Long id, String name, String email) {
}
