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
    REFUNDED;

    /**
     * Pure state-machine check : is the transition {@code this → next} allowed ?
     *
     * <p>Per shared ADR-0059, the valid graph is :
     * <pre>
     *   PENDING → SHIPPED → REFUNDED
     * </pre>
     *
     * <p>No skip-states (cannot REFUND a PENDING line — must SHIP first).
     * Self-transitions allowed. Backwards forbidden. {@code REFUNDED} is
     * terminal.
     *
     * @param next the target status
     * @return true iff the transition is part of the documented graph
     */
    public boolean canTransitionTo(OrderLineStatus next) {
        if (next == null) {
            return false;
        }
        if (this == next) {
            return true;
        }
        return switch (this) {
            case PENDING -> next == SHIPPED;
            case SHIPPED -> next == REFUNDED;
            case REFUNDED -> false;
        };
    }
}
