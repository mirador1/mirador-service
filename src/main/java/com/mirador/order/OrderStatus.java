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
    CANCELLED;

    /**
     * Pure state-machine check : is the transition {@code this → next} allowed ?
     *
     * <p>Per shared ADR-0059, the valid graph is :
     * <pre>
     *   PENDING → CONFIRMED → SHIPPED
     *      ↘            ↘
     *       CANCELLED   CANCELLED
     * </pre>
     *
     * <p>Self-transitions ({@code PENDING → PENDING}) are allowed (idempotent
     * "re-affirm" semantics). Backwards moves are forbidden — once SHIPPED,
     * you don't go back to PENDING.
     *
     * <p>{@code CANCELLED} is terminal : nothing transitions out of it.
     *
     * @param next the target status
     * @return true iff the transition is part of the documented graph
     */
    public boolean canTransitionTo(OrderStatus next) {
        if (next == null) {
            return false;
        }
        if (this == next) {
            return true;
        }
        return switch (this) {
            case PENDING -> next == CONFIRMED || next == CANCELLED;
            case CONFIRMED -> next == SHIPPED || next == CANCELLED;
            case SHIPPED -> false;
            case CANCELLED -> false;
        };
    }
}
