package com.mirador.order;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Outbound DTO for {@link Order} responses. Immutable record.
 *
 * <p>Foundation MR : just the order header. {@code totalAmount} is the
 * denormalised value from the entity. Embedded line list comes in the
 * follow-up MR with the OrderLine entity (V9).
 */
@Schema(description = "Order as returned by the API (header only — lines are a separate endpoint)")
public record OrderDto(
        Long id,
        Long customerId,
        OrderStatus status,
        BigDecimal totalAmount,
        Instant createdAt,
        Instant updatedAt
) {

    public static OrderDto from(Order o) {
        return new OrderDto(
                o.getId(),
                o.getCustomerId(),
                o.getStatus(),
                o.getTotalAmount(),
                o.getCreatedAt(),
                o.getUpdatedAt()
        );
    }
}
