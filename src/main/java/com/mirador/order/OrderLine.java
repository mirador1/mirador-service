package com.mirador.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity for {@code order_line} — the relation between an Order and a
 * Product, carrying the line-specific data (quantity + price snapshot).
 *
 * <h3>Why an entity (not a join table) ?</h3>
 * <ul>
 *   <li>Carries its OWN state : quantity, unit_price_at_order (snapshot),
 *       status (independent of parent order).</li>
 *   <li>Source of truth for what was actually ordered (the product can
 *       change name/price after, but the order line doesn't).</li>
 *   <li>An order line can be cancelled / refunded individually.</li>
 *   <li>Inventory tracking happens at the line level (not the order
 *       level).</li>
 * </ul>
 *
 * <h3>Immutability of {@code unitPriceAtOrder}</h3>
 * <p>App-enforced : no PUT endpoint accepts a new unit_price_at_order
 * value. The DB doesn't enforce this natively (would need a trigger).
 * Demo-grade discipline is sufficient.
 */
@Entity
@Table(name = "order_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK to {@code orders.id}. Required. ON DELETE CASCADE. */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** FK to {@code product.id}. Required. ON DELETE RESTRICT. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Strictly positive quantity. */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    /**
     * Snapshot of {@code Product.unitPrice} at the moment of order.
     * Immutable post-insert (app-enforced). Allows the product price to
     * change later without affecting historical order totals.
     */
    @Column(name = "unit_price_at_order", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceAtOrder;

    /** Per-line status (independent of parent order). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderLineStatus status;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT NOW()")
    private Instant createdAt;

    @PrePersist
    void onPersist() {
        if (this.status == null) {
            this.status = OrderLineStatus.PENDING;
        }
    }
}
