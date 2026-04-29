package org.iris.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * State machine truth table for {@link OrderLineStatus#canTransitionTo}.
 *
 * <p>Per shared ADR-0059 :
 * <pre>
 *   PENDING → SHIPPED → REFUNDED
 * </pre>
 *
 * <p>No skip-states (cannot REFUND a PENDING line). Self-transitions
 * allowed. Backwards forbidden. REFUNDED is terminal.
 */
class OrderLineStatusTransitionsTest {

    @ParameterizedTest(name = "{0} → {1} = {2}")
    @CsvSource({
            // Self-transitions
            "PENDING,  PENDING,  true",
            "SHIPPED,  SHIPPED,  true",
            "REFUNDED, REFUNDED, true",

            // Forward valid transitions
            "PENDING,  SHIPPED,  true",
            "SHIPPED,  REFUNDED, true",

            // Skip-state forbidden : PENDING → REFUNDED (must SHIP first)
            "PENDING,  REFUNDED, false",

            // Backwards forbidden
            "SHIPPED,  PENDING,  false",
            "REFUNDED, PENDING,  false",
            "REFUNDED, SHIPPED,  false",
    })
    void truthTable(OrderLineStatus src, OrderLineStatus dst, boolean expected) {
        assertThat(src.canTransitionTo(dst))
                .as("%s → %s should be %s", src, dst, expected)
                .isEqualTo(expected);
    }

    @Test
    void canTransitionTo_null_isFalse() {
        for (OrderLineStatus s : OrderLineStatus.values()) {
            assertThat(s.canTransitionTo(null))
                    .as("%s → null must be false", s)
                    .isFalse();
        }
    }
}
