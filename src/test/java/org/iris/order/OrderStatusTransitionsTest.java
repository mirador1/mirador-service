package org.iris.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * State machine truth table for {@link OrderStatus#canTransitionTo} —
 * one assertion per transition pair, parameterized for readable diffs.
 *
 * <p>Per shared ADR-0059 :
 * <pre>
 *   PENDING   → CONFIRMED → SHIPPED
 *      ↘            ↘
 *       CANCELLED   CANCELLED
 * </pre>
 *
 * <p>Self-transitions are allowed (idempotent re-affirm). Backwards moves
 * are forbidden (no return-to-PENDING). CANCELLED is terminal.
 *
 * <p>Existing {@link OrderInvariantsPropertyTest} jqwik covers this via
 * a property test ; this test enumerates the full 4×4 truth table for
 * a fixed-cost regression check + clearer failures.
 */
class OrderStatusTransitionsTest {

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @CsvSource({
            // Self-transitions (always allowed — idempotent semantics)
            "PENDING,   PENDING,   true",
            "CONFIRMED, CONFIRMED, true",
            "SHIPPED,   SHIPPED,   true",
            "CANCELLED, CANCELLED, true",

            // PENDING valid forward transitions
            "PENDING,   CONFIRMED, true",
            "PENDING,   CANCELLED, true",
            // PENDING invalid skip
            "PENDING,   SHIPPED,   false",

            // CONFIRMED valid forward transitions
            "CONFIRMED, SHIPPED,   true",
            "CONFIRMED, CANCELLED, true",
            // CONFIRMED invalid backward
            "CONFIRMED, PENDING,   false",

            // SHIPPED is terminal except self
            "SHIPPED,   PENDING,   false",
            "SHIPPED,   CONFIRMED, false",
            "SHIPPED,   CANCELLED, false",

            // CANCELLED is terminal
            "CANCELLED, PENDING,   false",
            "CANCELLED, CONFIRMED, false",
            "CANCELLED, SHIPPED,   false",
    })
    void truthTable(OrderStatus src, OrderStatus dst, boolean expected) {
        assertThat(src.canTransitionTo(dst))
                .as("%s → %s should be %s", src, dst, expected)
                .isEqualTo(expected);
    }

    @Test
    void canTransitionTo_null_isFalse() {
        // Sanity : null target never allowed (defends against bad input deserialised
        // from JSON when the @Pattern validator isn't engaged).
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(s.canTransitionTo(null))
                    .as("%s → null must be false", s)
                    .isFalse();
        }
    }
}
