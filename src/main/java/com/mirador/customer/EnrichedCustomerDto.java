package com.mirador.customer;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Extended customer DTO returned by the Kafka request-reply enrich endpoint.
 *
 * <p>Extends the base customer data ({@code id}, {@code name}, {@code email}) with a computed
 * {@code displayName} assembled by the Kafka consumer ({@link com.mirador.kafka.CustomerEnrichHandler}).
 * The display name format is {@code "Name <email>"} (e.g., {@code "Alice <alice@example.com>"}).
 *
 * <p>Using a dedicated DTO for the enriched response keeps the base {@link CustomerDto} clean
 * and makes it explicit which fields are computed vs. stored.
 */
@Schema(description = "Enriched customer — base fields + displayName computed via Kafka request-reply")
public record EnrichedCustomerDto(
        @Schema(description = "Customer ID", example = "42")
        Long id,

        @Schema(description = "Customer name", example = "Alice Martin")
        String name,

        @Schema(description = "Customer email", example = "alice@example.com")
        String email,

        @Schema(description = "Display name computed by the Kafka consumer: 'Name <email>'", example = "Alice Martin <alice@example.com>")
        String displayName
) {
}
