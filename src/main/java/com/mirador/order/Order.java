package com.mirador.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * JPA entity representing an order, mapped to the {@code orders} table.
 *
 * <h3>Why "orders" (plural) ?</h3>
 * <p>{@code order} is a SQL reserved word. The plural form avoids the
 * need to quote the identifier in every query. Java side keeps the
 * singular {@code Order} class name + maps to {@code orders} via
 * {@link Table#name()}.
 *
 * <h3>{@code total_amount} is denormalised</h3>
 * <p>Stored as a column rather than computed via JOIN+SUM on every read.
 * Application is responsible for recomputing on every OrderLine
 * add/remove (in a transaction). Trade : faster reads, slightly more
 * complex writes. List views (likely the hot path) win.
 *
 * <h3>Status as String + JPA EnumType.STRING</h3>
 * <p>Avoids ordinal-based enum storage (fragile across reorderings).
 * The DB CHECK constraint enforces valid values.
 *
 * @see OrderRepository for query methods.
 * @see <a href="../../../../resources/db/migration/V8__create_orders.sql">V8 migration</a>
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * FK to {@code customer.id}. Required. ON DELETE RESTRICT at the DB
     * level — refuses deleting a customer who still has orders.
     */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** Order status. Always non-null, default PENDING per V8. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    /**
     * Sum of OrderLine.quantity × unit_price_at_order for all lines.
     * Application-managed (recomputed on every line add/remove).
     */
    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT NOW()")
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onPersist() {
        if (this.status == null) {
            this.status = OrderStatus.PENDING;
        }
        if (this.totalAmount == null) {
            this.totalAmount = BigDecimal.ZERO;
        }
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
