package com.example.springapi.customer;

/**
 * Extended customer DTO returned by the Kafka request-reply enrich endpoint.
 *
 * <p>Extends the base customer data ({@code id}, {@code name}, {@code email}) with a computed
 * {@code displayName} assembled by the Kafka consumer ({@link com.example.springapi.kafka.CustomerEnrichHandler}).
 * The display name format is {@code "Name <email>"} (e.g., {@code "Alice <alice@example.com>"}).
 *
 * <p>Using a dedicated DTO for the enriched response keeps the base {@link CustomerDto} clean
 * and makes it explicit which fields are computed vs. stored.
 */
public record EnrichedCustomerDto(Long id, String name, String email, String displayName) {
}
