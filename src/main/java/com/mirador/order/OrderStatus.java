package com.mirador.order;

/**
 * Lifecycle states of an {@link Order}.
 *
 * <p>Stored as a {@code VARCHAR(20)} in the DB (see V8 migration) +
 * mapped via {@code @Enumerated(EnumType.STRING)} so the textual
 * representation is stable across enum reorderings.
 *
 * <p>Allowed transitions (enforced at the application layer in a
 * follow-up MR — foundation layer just stores) :
 * <pre>
 *   PENDING   → CONFIRMED → SHIPPED
 *      ↘            ↘
 *       CANCELLED   CANCELLED
 * </pre>
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    SHIPPED,
    CANCELLED
}
