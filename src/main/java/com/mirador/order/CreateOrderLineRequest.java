package com.mirador.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Inbound DTO for {@code POST /orders/{orderId}/lines}.
 *
 * <p>{@code unitPriceAtOrder} is NOT in the request — it's snapshotted
 * server-side from the current Product.unitPrice (immutable post-insert).
 *
 * @param productId Required, FK to existing product.
 * @param quantity  Required, > 0.
 */
@Schema(description = "Payload for adding an OrderLine to an Order")
public record CreateOrderLineRequest(
        @NotNull @Positive Long productId,
        @NotNull @Positive Integer quantity
) {}
