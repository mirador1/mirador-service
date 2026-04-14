package com.mirador.messaging;

/**
 * Kafka event published after a customer is successfully created (Pattern 1 — fire-and-forget).
 *
 * <p>Published by {@link com.mirador.service.CustomerService#create} to the
 * {@code customer.created} topic immediately after the database INSERT, using the
 * customer ID as the Kafka message key (ensuring same-customer events land on the
 * same partition, preserving ordering per customer).
 *
 * <p>Consumed by {@link com.mirador.kafka.CustomerEventListener}, which logs the
 * event and increments the {@code kafka.customer.created.processed} metric.
 *
 * <p>Serialized to JSON by {@code JacksonJsonSerializer} with a {@code __TypeId__} header
 * set to the full class name, so consumers can deserialize to the correct type.
 */
public record CustomerCreatedEvent(Long id, String name, String email) {
}
