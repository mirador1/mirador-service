package com.mirador.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /orders/{orderId}/lines/{lineId}/status}.
 *
 * <p>State machine validation lives on
 * {@link OrderLineStatus#canTransitionTo} — the controller maps a
 * forbidden transition to 409 ProblemDetail.
 *
 * <p>Per [shared ADR-0063](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0063-order-line-refund-state-machine.md)
 * §"Audit", any transition writes to {@code audit_event} carrying the
 * (optional but recommended) reason. The reason is capped at 500 chars
 * to keep the audit row manageable ; longer rationale belongs in the
 * customer-facing case management system.
 *
 * @param status target line status (PENDING / SHIPPED / REFUNDED).
 * @param reason optional human-readable rationale (capped 500 chars).
 *               REFUNDED transitions are encouraged to carry one
 *               (defective, customer-service goodwill, etc.) — the
 *               controller doesn't enforce it but the dashboard
 *               surfaces "no reason" rows separately.
 */
public record UpdateOrderLineStatusRequest(
        @NotNull OrderLineStatus status,
        @Size(max = 500) String reason) {}
