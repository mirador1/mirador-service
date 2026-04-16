/**
 * Kafka messaging + WebSocket live events.
 *
 * <p>Two distinct concerns live here, both related to asynchronous fan-out:
 *
 * <h2>Kafka</h2>
 * <dl>
 *   <dt>{@link CustomerEventPublisher}</dt>
 *   <dd>Emits {@link CustomerCreatedEvent} to the {@code customer.created} topic
 *       whenever a customer is created or updated. Uses Spring's
 *       {@code KafkaTemplate} with {@code JsonSerializer}.</dd>
 *   <dt>{@link CustomerEventListener}</dt>
 *   <dd>Consumes the same topic (different consumer group) and forwards events
 *       to the WebSocket broadcaster below. Illustrates the pub/sub pattern —
 *       in production these would be in different services.</dd>
 *   <dt>{@link CustomerEnrichHandler} / {@link CustomerEnrichRequest} / {@link CustomerEnrichReply}</dt>
 *   <dd>Request-reply pattern over Kafka (not classic pub/sub): the caller
 *       sends a correlation-ID request to {@code customer.request}, listens
 *       for the response on {@code customer.reply}. Demonstrates the
 *       {@code ReplyingKafkaTemplate}.</dd>
 *   <dt>{@link KafkaConfig}</dt>
 *   <dd>Producers, consumers, topics, and the reply template wiring. Explicit
 *       typed configuration — no Spring Boot auto-configuration magic.</dd>
 * </dl>
 *
 * <h2>WebSocket</h2>
 * <dl>
 *   <dt>{@link WebSocketConfig}</dt>
 *   <dd>STOMP over WebSocket on {@code /ws}. A simple in-memory broker relays
 *       messages published to {@code /topic/customers} to every connected
 *       client. The Angular SPA subscribes to this topic for real-time updates.</dd>
 * </dl>
 *
 * <h2>Topics</h2>
 * <ul>
 *   <li>{@code customer.created} — fire-and-forget change events.</li>
 *   <li>{@code customer.request} + {@code customer.reply} — RPC-style enrichment.</li>
 * </ul>
 *
 * <p>All topics are created at startup by {@link KafkaConfig}. Do not rely on
 * Kafka auto-create — the local Kafka container has it disabled for safety.
 */
package com.mirador.messaging;
