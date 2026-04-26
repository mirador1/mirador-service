package com.mirador.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * Inbound DTO for {@code POST /products}. Bean Validation enforces the
 * domain invariants at the API edge ; the DB has matching CHECK constraints
 * (defense in depth).
 *
 * <p>{@code description} is optional ({@code @Size} only applies if present).
 *
 * @param name           Required, 1-255 chars, must be unique (DB-level).
 * @param description    Optional long-form text.
 * @param unitPrice      Required, >= 0. {@link BigDecimal} preserves decimal
 *                       precision (avoids float rounding in totals).
 * @param stockQuantity  Required, >= 0. Default to 0 if absent.
 */
@Schema(description = "Payload for creating a Product")
public record CreateProductRequest(
        @NotBlank @Size(min = 1, max = 255) String name,
        @Size(max = 10_000) String description,
        @NotNull @PositiveOrZero BigDecimal unitPrice,
        @NotNull @PositiveOrZero Integer stockQuantity
) {}
