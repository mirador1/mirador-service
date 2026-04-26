package com.mirador.product;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound DTO for product responses. Records (immutable) are used here
 * because — unlike {@link Product} — there's no JPA reflection required,
 * so the value-class semantics fit cleanly.
 *
 * <p>{@code createdAt} / {@code updatedAt} are exposed for client-side
 * display + cache validation. The full {@link Product} entity is never
 * serialized directly (avoid leaking JPA internals + LAZY proxies).
 */
@Schema(description = "Product as returned by the API")
public record ProductDto(
        Long id,
        String name,
        String description,
        BigDecimal unitPrice,
        Integer stockQuantity,
        Instant createdAt,
        Instant updatedAt
) {

    /** Convert from JPA entity to DTO. */
    public static ProductDto from(Product p) {
        return new ProductDto(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getUnitPrice(),
                p.getStockQuantity(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
