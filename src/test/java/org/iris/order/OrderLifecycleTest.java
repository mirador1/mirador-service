package org.iris.order;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link Order} JPA lifecycle hooks + accessors that
 * don't require a Spring context. Pure JVM, runs <1ms each.
 *
 * <p>Closes JaCoCo coverage gaps in {@code org.iris.order/Order.java}
 * that the existing {@link OrderCascadeITest} doesn't reach (the hooks
 * are exercised as side-effects there, but JaCoCo flags the timestamp
 * branches as missed because the IT only observes existence).
 */
class OrderLifecycleTest {

    @Test
    void onPersist_setsUpdatedAtWhenNull() throws Exception {
        Order o = newOrder(null);
        invokeHook(o, "onPersist");
        assertThat(o.getUpdatedAt())
                .isNotNull()
                .isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void onPersist_preservesExistingUpdatedAt() throws Exception {
        Instant explicit = Instant.parse("2025-01-01T00:00:00Z");
        Order o = newOrder(explicit);
        invokeHook(o, "onPersist");
        assertThat(o.getUpdatedAt()).isEqualTo(explicit);
    }

    @Test
    void onPersist_defaultsStatusToPendingWhenNull() throws Exception {
        // Pinned : the controller's POST handler sets status=PENDING
        // explicitly, but a direct repository.save() (e.g. from a test
        // fixture or a future bulk-import path) might not. The hook
        // owns the "no-status-on-insert means PENDING" contract.
        Order o = new Order(null, 1L, null, BigDecimal.ZERO, null, null);
        invokeHook(o, "onPersist");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void onPersist_preservesExistingStatus() throws Exception {
        Order o = new Order(null, 1L, OrderStatus.CONFIRMED, BigDecimal.ZERO, null, null);
        invokeHook(o, "onPersist");
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void onPersist_defaultsTotalAmountToZeroWhenNull() throws Exception {
        Order o = new Order(null, 1L, OrderStatus.PENDING, null, null, null);
        invokeHook(o, "onPersist");
        assertThat(o.getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void onPersist_preservesExistingTotalAmount() throws Exception {
        Order o = new Order(null, 1L, OrderStatus.PENDING, new BigDecimal("42.42"), null, null);
        invokeHook(o, "onPersist");
        assertThat(o.getTotalAmount()).isEqualByComparingTo("42.42");
    }

    @Test
    void onUpdate_alwaysBumpsUpdatedAt() throws Exception {
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        Order o = newOrder(old);
        invokeHook(o, "onUpdate");
        assertThat(o.getUpdatedAt())
                .isNotEqualTo(old)
                .isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        Instant now = Instant.now();
        Order o = new Order(7L, 42L, OrderStatus.CONFIRMED, new BigDecimal("19.99"), now, now);
        assertThat(o.getId()).isEqualTo(7L);
        assertThat(o.getCustomerId()).isEqualTo(42L);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(o.getTotalAmount()).isEqualByComparingTo("19.99");
        assertThat(o.getCreatedAt()).isEqualTo(now);
        assertThat(o.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void noArgsConstructor_yieldsAllNull() {
        Order o = new Order();
        assertThat(o.getId()).isNull();
        assertThat(o.getCustomerId()).isNull();
        assertThat(o.getStatus()).isNull();
        assertThat(o.getTotalAmount()).isNull();
    }

    @Test
    void setters_mutateAllFields() {
        Order o = new Order();
        o.setId(1L);
        o.setCustomerId(2L);
        o.setStatus(OrderStatus.SHIPPED);
        o.setTotalAmount(new BigDecimal("99.00"));
        Instant t = Instant.now();
        o.setCreatedAt(t);
        o.setUpdatedAt(t);
        assertThat(o.getId()).isEqualTo(1L);
        assertThat(o.getCustomerId()).isEqualTo(2L);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(o.getTotalAmount()).isEqualByComparingTo("99.00");
        assertThat(o.getCreatedAt()).isEqualTo(t);
        assertThat(o.getUpdatedAt()).isEqualTo(t);
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static Order newOrder(Instant updatedAt) {
        return new Order(null, 1L, OrderStatus.PENDING, BigDecimal.ZERO, null, updatedAt);
    }

    private static void invokeHook(Order o, String name) throws Exception {
        Method m = Order.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }
}
