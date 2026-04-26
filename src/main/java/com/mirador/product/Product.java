package com.mirador.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * JPA entity representing a product stored in the {@code product} table.
 *
 * <h3>Why BigDecimal for unit_price (not double / float) ?</h3>
 * <p>Floating-point arithmetic introduces rounding errors that compound
 * across order lines + totals. {@link BigDecimal} preserves exact decimal
 * precision (matches PostgreSQL's {@code NUMERIC(12,2)}), so a sum of
 * 100 lines at €0.10 gives exactly €10.00 — not €9.999999...
 *
 * <h3>updated_at is application-managed, not DB trigger</h3>
 * <p>{@link PreUpdate} bumps {@code updatedAt} before each save. Trigger
 * would be more "automatic" but adds DB-side complexity for a value the
 * application owns anyway. Same trade-off across the entity hierarchy.
 *
 * <h3>Stock quantity invariant</h3>
 * <p>{@code stock_quantity >= 0} is enforced :
 * <ul>
 *   <li>at the DB level via the {@code CHECK} constraint in V7 migration
 *       (defense in depth — guard against bypassed bean validation)</li>
 *   <li>at the API edge via {@code @PositiveOrZero} bean validation in
 *       {@link CreateProductRequest} (better error message for the client)</li>
 * </ul>
 *
 * @see Customer for the entity-pattern reference (similar JPA conventions).
 * @see com.mirador.product.ProductRepository for query methods.
 * @see <a href="../../../../resources/db/migration/V7__create_product.sql">V7 migration</a>
 */
@Entity
@Table(name = "product")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    /** DB-generated primary key — PostgreSQL BIGSERIAL after INSERT. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Unique name (DB-level UNIQUE constraint). Required, non-blank. */
    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    /** Optional long-form description (TEXT in DB, nullable). */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /**
     * Unit price stored as decimal (12,2) — see class javadoc for the
     * BigDecimal vs double rationale.
     */
    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /**
     * Current stock balance. CHECK >= 0 at the DB level + bean validation
     * at the controller. Decremented when an order ships, incremented on
     * refund / restock.
     */
    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity;

    /**
     * Set by the DB DEFAULT NOW() at INSERT — that's why {@code insertable = false}.
     * JPA never writes this column ; PostgreSQL fills it automatically.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ DEFAULT NOW()")
    private Instant createdAt;

    /**
     * Application-managed (see PreUpdate hook below). Set on every save.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Default updatedAt to now() on first persist. */
    @PrePersist
    void onPersist() {
        if (this.updatedAt == null) {
            this.updatedAt = Instant.now();
        }
    }

    /** Bump updatedAt on every UPDATE. */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
