package com.mirador.mcp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Aggregate read model for {@code get_customer_360} MCP tool.
 *
 * <p>Bundles the customer header (id, name, email) with three read-only
 * aggregates computed across that customer's orders : count of orders,
 * sum of {@code total_amount}, and timestamp of the most recent order.
 *
 * <p>Why a dedicated DTO instead of returning the {@link com.mirador.customer.Customer}
 * entity ?
 * <ul>
 *   <li>Per ADR-0062, JPA entities are never returned to LLM tools — lazy
 *       collections explode into hundreds of rows when serialised.</li>
 *   <li>The aggregate values ({@code orderCount}, {@code totalRevenue},
 *       {@code lastOrderAt}) are computed from the {@code orders} table, not
 *       stored on the customer entity. Returning a flat DTO is the cleanest
 *       contract for callers.</li>
 * </ul>
 *
 * @param id            customer ID (FK key in {@code customer.id})
 * @param name          customer's full name (may be {@code null} on legacy rows)
 * @param email         customer's email (unique per customer)
 * @param orderCount    number of orders placed by this customer (≥ 0)
 * @param totalRevenue  sum of {@code orders.total_amount} for this customer ;
 *                      {@link BigDecimal#ZERO} when {@code orderCount == 0}
 * @param lastOrderAt   timestamp of the most recent order ({@code created_at}) ;
 *                      {@code null} when {@code orderCount == 0}
 */
@Schema(description = "Customer 360 — header + aggregate over the customer's orders")
public record Customer360Dto(
        Long id,
        String name,
        String email,
        long orderCount,
        BigDecimal totalRevenue,
        Instant lastOrderAt
) {
}
