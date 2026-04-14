package com.mirador.customer;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * API v2 projection of a {@link Customer} entity, extending the v1 shape with a
 * {@code createdAt} timestamp.
 *
 * <p>This record is returned by {@code GET /customers} when the client sends
 * {@code X-API-Version: 2.0} (configured via Spring Framework 7's native API
 * versioning support — see {@code spring.mvc.apiversion.*} in application.yml).
 *
 * <p>The {@code createdAt} field maps to the {@code created_at TIMESTAMPTZ} column
 * added in Flyway migration {@code V3__add_customer_createdat.sql}.
 *
 * <h3>Why a separate DTO instead of adding the field to {@link CustomerDto}?</h3>
 * <ul>
 *   <li>Backward compatibility: v1 clients that parse the response as a fixed schema
 *       would break if an unexpected field appeared.</li>
 *   <li>Explicit contract: each version has a clear, documented shape. Clients choose
 *       the version they are ready for.</li>
 * </ul>
 *
 * [Spring Boot 4 / Spring 7 API versioning demo — @RequestMapping(version = "2.0")]
 */
@Schema(description = "Customer resource (API v2) — adds createdAt compared to v1. Request with X-API-Version: 2.0")
public record CustomerDtoV2(
        @Schema(description = "Unique auto-incremented identifier", example = "42")
        Long id,

        @Schema(description = "Full name of the customer", example = "Alice Martin")
        String name,

        @Schema(description = "Email address (unique per customer)", example = "alice@example.com")
        String email,

        @Schema(description = "UTC timestamp when the customer record was created (ISO-8601)", example = "2024-06-01T10:30:00Z")
        Instant createdAt
) {
}
