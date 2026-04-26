package com.mirador.order;

/**
 * Per-line status (independent of parent {@link Order} status).
 *
 * <p>Allowed values match the V9 migration's CHECK constraint :
 * <pre>
 *   PENDING → SHIPPED → REFUNDED
 * </pre>
 */
public enum OrderLineStatus {
    PENDING,
    SHIPPED,
    REFUNDED
}
