package com.mirador.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Inbound DTO for {@code POST /orders}.
 *
 * <p>Foundation MR : creates an empty order (no lines) attached to a
 * customer. Lines are added separately via {@code POST
 * /orders/{id}/lines} once the OrderLine entity ships (V9 migration).
 *
 * @param customerId Required, FK to existing customer.
 */
@Schema(description = "Payload for creating a new Order (empty, lines added separately)")
public record CreateOrderRequest(
        @NotNull @Positive Long customerId
) {}
