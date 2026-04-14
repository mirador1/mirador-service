package com.mirador.customer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /customers/{id}} — partial customer update.
 *
 * <p>Unlike {@link CreateCustomerRequest} (used by PUT), all fields here are optional:
 * only non-null fields are applied. This avoids the "replace everything" semantics of PUT
 * when the caller only wants to change one field.
 *
 * <p>Example: {@code {"name": "Alice"}} only renames, leaving the email unchanged.
 */
@Schema(description = "Partial update request — only provided fields are modified")
public record PatchCustomerRequest(
        @Schema(description = "New name (omit to keep current)", example = "Alice Martin")
        @Size(max = 255, message = "Name must not exceed 255 characters")
        String name,

        @Schema(description = "New email (omit to keep current)", example = "alice@example.com")
        @Email @Size(max = 255, message = "Email must not exceed 255 characters")
        String email
) {}
