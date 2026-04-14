package com.mirador.customer;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Read-only DTO used to expose customer data through the REST API (v1).
 *
 * <p>This DTO decouples the API contract from the JPA entity ({@link com.mirador.model.Customer}).
 * Benefits:
 * <ul>
 *   <li>The entity can change (add fields, rename columns) without breaking the API consumers.</li>
 *   <li>Sensitive or internal fields (e.g., a future {@code passwordHash}) are never accidentally
 *       serialized to the response body.</li>
 * </ul>
 *
 * <p>Jackson serializes records to JSON using the component names as field names:
 * {@code {"id":1,"name":"Alice","email":"alice@example.com"}}.
 */
@Schema(description = "Customer resource (API v1) — id, name, email")
public record CustomerDto(
        @Schema(description = "Unique auto-incremented identifier", example = "42")
        Long id,

        @Schema(description = "Full name of the customer", example = "Alice Martin")
        String name,

        @Schema(description = "Email address (unique per customer)", example = "alice@example.com")
        String email
) {
}
