package com.mirador.order;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PUT /orders/{id}/status}.
 *
 * <p>Single field — Jackson maps the JSON enum string to the typed
 * {@link OrderStatus} ; an unknown value triggers a 422 via the
 * default {@code @Valid} + {@code @NotNull} pipeline.
 *
 * <p>State machine validation lives on {@link OrderStatus#canTransitionTo} —
 * the controller maps a forbidden transition to 409 ProblemDetail.
 *
 * @param status target status (PENDING / CONFIRMED / SHIPPED / CANCELLED).
 */
public record UpdateOrderStatusRequest(@NotNull OrderStatus status) {}
