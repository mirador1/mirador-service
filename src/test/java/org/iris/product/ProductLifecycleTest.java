package org.iris.product;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import java.time.temporal.ChronoUnit;

/**
 * Unit tests for the JPA lifecycle hooks on {@link Product} that don't
 * require a Spring context (the {@code @PrePersist} + {@code @PreUpdate}
 * methods are package-private — invoke them via reflection here so the
 * tests stay in {@code src/test/java/org/iris/product} without leaking
 * the methods to the public API).
 *
 * <p>Why a separate test : the hooks are part of the contract (rows
 * always have a non-null {@code updatedAt}), but {@code OrderCascadeITest}
 * exercises them only as a side-effect of a Spring Boot integration test,
 * which is slow + opaque. This unit test pins the behaviour directly.
 *
 * <p>Coverage : closes the {@code onPersist} and {@code onUpdate} branches
 * which JaCoCo currently flags as missed because the integration tests
 * don't observe the timestamp value, only its existence.
 */
class ProductLifecycleTest {

    @Test
    void onPersist_setsUpdatedAtWhenNull() throws Exception {
        Product p = new Product(null, "P", "d", BigDecimal.ONE, 1, null, null);
        assertThat(p.getUpdatedAt()).isNull();

        invokeHook(p, "onPersist");

        assertThat(p.getUpdatedAt())
                .as("onPersist must default updatedAt when caller didn't set it")
                .isNotNull()
                .isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void onPersist_preservesExistingUpdatedAt() throws Exception {
        Instant explicit = Instant.parse("2025-01-01T00:00:00Z");
        Product p = new Product(null, "P", "d", BigDecimal.ONE, 1, null, explicit);

        invokeHook(p, "onPersist");

        assertThat(p.getUpdatedAt())
                .as("onPersist should NOT overwrite a caller-supplied updatedAt")
                .isEqualTo(explicit);
    }

    @Test
    void onUpdate_alwaysBumpsUpdatedAt() throws Exception {
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        Product p = new Product(null, "P", "d", BigDecimal.ONE, 1, null, old);

        invokeHook(p, "onUpdate");

        assertThat(p.getUpdatedAt())
                .as("onUpdate must always set a fresh updatedAt — that's its whole job")
                .isNotEqualTo(old)
                .isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void allArgsConstructor_setsAllFields() {
        Instant now = Instant.now();
        Product p = new Product(42L, "name", "desc", new BigDecimal("9.99"), 5, now, now);
        assertThat(p.getId()).isEqualTo(42L);
        assertThat(p.getName()).isEqualTo("name");
        assertThat(p.getDescription()).isEqualTo("desc");
        assertThat(p.getUnitPrice()).isEqualByComparingTo("9.99");
        assertThat(p.getStockQuantity()).isEqualTo(5);
        assertThat(p.getCreatedAt()).isEqualTo(now);
        assertThat(p.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void noArgsConstructor_yieldsAllNull() {
        Product p = new Product();
        assertThat(p.getId()).isNull();
        assertThat(p.getName()).isNull();
        assertThat(p.getDescription()).isNull();
        assertThat(p.getUnitPrice()).isNull();
        assertThat(p.getStockQuantity()).isNull();
    }

    @Test
    void setters_mutateAllFields() {
        Product p = new Product();
        p.setId(1L);
        p.setName("n");
        p.setDescription("d");
        p.setUnitPrice(new BigDecimal("0.01"));
        p.setStockQuantity(0);
        Instant t = Instant.now();
        p.setCreatedAt(t);
        p.setUpdatedAt(t);

        assertThat(p.getId()).isEqualTo(1L);
        assertThat(p.getName()).isEqualTo("n");
        assertThat(p.getDescription()).isEqualTo("d");
        assertThat(p.getUnitPrice()).isEqualByComparingTo("0.01");
        assertThat(p.getStockQuantity()).isZero();
        assertThat(p.getCreatedAt()).isEqualTo(t);
        assertThat(p.getUpdatedAt()).isEqualTo(t);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void invokeHook(Product p, String name) throws Exception {
        Method m = Product.class.getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(p);
    }

}
