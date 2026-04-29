package org.iris.order;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OrderLine} accessors + the {@code @PrePersist}
 * hook. Pure JVM, no Spring context.
 *
 * <p>OrderLine is special : per ADR-0059 invariant 5, {@code unit_price_at_order}
 * is set once at insert and never mutated afterwards (no @PreUpdate hook).
 * The shipping behaviour around that constraint lives in the application
 * layer (the controller doesn't expose a PUT for the price field) — this
 * test only pins the entity-level shape.
 */
class OrderLineLifecycleTest {

    @Test
    void onPersist_defaultsStatusToPendingWhenNull() throws Exception {
        OrderLine line = new OrderLine(
                null, 1L, 1L, 1, new BigDecimal("9.99"), null, null);
        invokeHook(line, "onPersist");
        assertThat(line.getStatus())
                .as("onPersist must default status to PENDING when caller didn't set it")
                .isEqualTo(OrderLineStatus.PENDING);
    }

    @Test
    void onPersist_preservesExistingStatus() throws Exception {
        OrderLine line = new OrderLine(
                null, 1L, 1L, 1, new BigDecimal("9.99"), OrderLineStatus.SHIPPED, null);
        invokeHook(line, "onPersist");
        assertThat(line.getStatus())
                .as("onPersist must NOT overwrite caller-supplied status")
                .isEqualTo(OrderLineStatus.SHIPPED);
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        Instant now = Instant.now();
        OrderLine line = new OrderLine(
                7L, 42L, 21L, 5, new BigDecimal("12.50"),
                OrderLineStatus.SHIPPED, now);
        assertThat(line.getId()).isEqualTo(7L);
        assertThat(line.getOrderId()).isEqualTo(42L);
        assertThat(line.getProductId()).isEqualTo(21L);
        assertThat(line.getQuantity()).isEqualTo(5);
        assertThat(line.getUnitPriceAtOrder()).isEqualByComparingTo("12.50");
        assertThat(line.getStatus()).isEqualTo(OrderLineStatus.SHIPPED);
        assertThat(line.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void noArgsConstructor_yieldsAllNull() {
        OrderLine line = new OrderLine();
        assertThat(line.getId()).isNull();
        assertThat(line.getOrderId()).isNull();
        assertThat(line.getProductId()).isNull();
        assertThat(line.getQuantity()).isNull();
        assertThat(line.getUnitPriceAtOrder()).isNull();
        assertThat(line.getStatus()).isNull();
    }

    @Test
    void setters_mutateAllFields() {
        OrderLine line = new OrderLine();
        line.setId(1L);
        line.setOrderId(2L);
        line.setProductId(3L);
        line.setQuantity(4);
        line.setUnitPriceAtOrder(new BigDecimal("0.01"));
        line.setStatus(OrderLineStatus.REFUNDED);
        Instant t = Instant.now();
        line.setCreatedAt(t);
        assertThat(line.getId()).isEqualTo(1L);
        assertThat(line.getOrderId()).isEqualTo(2L);
        assertThat(line.getProductId()).isEqualTo(3L);
        assertThat(line.getQuantity()).isEqualTo(4);
        assertThat(line.getUnitPriceAtOrder()).isEqualByComparingTo("0.01");
        assertThat(line.getStatus()).isEqualTo(OrderLineStatus.REFUNDED);
        assertThat(line.getCreatedAt()).isEqualTo(t);
    }

    private static void invokeHook(OrderLine o, String name) throws Exception {
        Method m = OrderLine.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }
}
