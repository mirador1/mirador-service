package com.mirador.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CustomerEnrichHandler} — the consumer side of the
 * Kafka request-reply pattern. Pure unit tests without a Kafka broker:
 * the handler's `handleEnrichRequest` is just a function (Spring's
 * `@KafkaListener` machinery is what brings the message in, but that's
 * framework-side and covered by integration tests).
 *
 * <p>Pin the contract that the {@code displayName} format is
 * {@code "Name <email>"} (UI relies on it for the enriched-customer
 * detail card) + Micrometer counter increments per request.
 */
class CustomerEnrichHandlerTest {

    private MeterRegistry meterRegistry;
    private CustomerEnrichHandler handler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new CustomerEnrichHandler(meterRegistry);
    }

    @Test
    void handleEnrichRequest_returnsReplyWithDisplayNameAssembledFromNameAndEmail() {
        var request = new CustomerEnrichRequest(42L, "Alice", "alice@example.com");

        CustomerEnrichReply reply = handler.handleEnrichRequest(request);

        assertThat(reply.id()).isEqualTo(42L);
        assertThat(reply.name()).isEqualTo("Alice");
        assertThat(reply.email()).isEqualTo("alice@example.com");
        // Format pinned: "Name <email>" — UI displays this in the enriched
        // detail card. Changing the format would silently break the UI.
        assertThat(reply.displayName()).isEqualTo("Alice <alice@example.com>");
    }

    @Test
    void handleEnrichRequest_incrementsCounterPerRequest() {
        var counter = meterRegistry.find("kafka.customer.enrich.handled").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isZero();

        handler.handleEnrichRequest(new CustomerEnrichRequest(1L, "A", "a@x"));
        handler.handleEnrichRequest(new CustomerEnrichRequest(2L, "B", "b@x"));
        handler.handleEnrichRequest(new CustomerEnrichRequest(3L, "C", "c@x"));

        assertThat(counter.count()).isEqualTo(3.0);
    }

    @Test
    void handleEnrichRequest_handlesEmptyName() {
        // Edge case: name is empty (e.g. import that didn't populate it).
        // Handler must still produce a well-formed reply, not NPE.
        var reply = handler.handleEnrichRequest(new CustomerEnrichRequest(1L, "", "x@y.z"));

        assertThat(reply.displayName()).isEqualTo(" <x@y.z>");
    }

    @Test
    void handleEnrichRequest_handlesAngleBracketsInName() {
        // Adversarial input: the format uses < > as delimiters, but the
        // name itself can contain them (e.g. "<script>"). The handler does
        // NOT escape — it's the producer/consumer's responsibility downstream.
        // Test pins the no-escape behaviour so a future "fix" with escaping
        // doesn't silently change the contract.
        var reply = handler.handleEnrichRequest(
                new CustomerEnrichRequest(1L, "<script>", "x@y"));

        assertThat(reply.displayName()).isEqualTo("<script> <x@y>");
    }
}
