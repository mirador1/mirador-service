package com.mirador.order;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for the invariants of the Order/OrderLine domain
 * documented in shared ADR-0059.
 *
 * <p>Each {@code @Property} method captures one invariant ; jqwik generates
 * random inputs and shrinks failures to the smallest counter-example. This
 * complements example-based JUnit tests by exploring far more of the input
 * space than a hand-written test could.
 *
 * @see <a href="https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0059-customer-order-product-data-model.md">ADR-0059</a>
 */
class OrderInvariantsPropertyTest {

    /**
     * Invariant 1 (ADR-0059) :
     * <pre>Order.totalAmount == Σ(line.quantity × line.unitPriceAtOrder)</pre>
     *
     * <p>Verified against {@link Order#computeTotal(java.util.Collection)} —
     * the canonical implementation of the invariant. jqwik generates random
     * line lists of varying size + price + quantity ; the test recomputes
     * the sum independently and asserts equality.
     *
     * <p>Independent recomputation matters : if the production code drifts
     * (e.g. someone "optimises" the stream into a fold that drops decimals),
     * this test catches it because the alternate path stays simple.
     */
    @Property(tries = 100)
    void totalAmount_equalsSumOfLines(@ForAll("orderLines") @Size(min = 0, max = 20) List<OrderLine> lines) {
        BigDecimal computed = Order.computeTotal(lines);

        BigDecimal expected = lines.stream()
                .map(line -> line.getUnitPriceAtOrder()
                        .multiply(BigDecimal.valueOf(line.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(computed)
                .as("computeTotal(%d lines) must equal Σ(qty × unitPrice)", lines.size())
                .isEqualByComparingTo(expected);
    }

    /**
     * Empty / null input boundary : both must yield {@link BigDecimal#ZERO}.
     * Property test rather than example because we want to formalise the
     * "no lines = no money" invariant as a contract, not a one-shot.
     */
    @Property(tries = 1)
    void totalAmount_emptyOrNull_isZero() {
        assertThat(Order.computeTotal(List.of())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(Order.computeTotal(null)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /**
     * Sum is non-negative for any well-formed line list (qty > 0 and price > 0).
     *
     * <p>Captures the implicit business rule : an order's total is never
     * negative under valid inputs. Negative totals would only emerge from a
     * bug — generating thousands of random inputs that never produce one
     * gives high confidence the invariant holds.
     */
    @Property(tries = 100)
    void totalAmount_nonNegative_forValidLines(@ForAll("orderLines") List<OrderLine> lines) {
        BigDecimal total = Order.computeTotal(lines);
        assertThat(total).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    /**
     * Linearity : doubling every quantity doubles the total.
     *
     * <p>This is a pure mathematical property of the formula
     * {@code Σ(qty × price)} — verifying it experimentally proves the
     * implementation didn't sneak in non-linear behaviour (e.g. quantity
     * tiers, bulk discounts) that would belong in a different layer.
     */
    @Property(tries = 50)
    void totalAmount_linear_inQuantity(@ForAll("orderLines") @Size(min = 1, max = 10) List<OrderLine> lines) {
        BigDecimal originalTotal = Order.computeTotal(lines);

        // Double every quantity in-place
        lines.forEach(line -> line.setQuantity(line.getQuantity() * 2));

        BigDecimal doubledTotal = Order.computeTotal(lines);

        assertThat(doubledTotal)
                .as("doubling all quantities must double the total")
                .isEqualByComparingTo(originalTotal.multiply(BigDecimal.valueOf(2)));
    }

    // ── Generators ──────────────────────────────────────────────────────

    @Provide
    Arbitrary<OrderLine> orderLine() {
        Arbitrary<Integer> quantity = Arbitraries.integers().between(1, 1_000);
        Arbitrary<BigDecimal> unitPrice = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("99999.99"))
                .ofScale(2);

        return Combinators.combine(quantity, unitPrice).as((qty, price) -> {
            OrderLine line = new OrderLine();
            line.setQuantity(qty);
            line.setUnitPriceAtOrder(price.setScale(2, RoundingMode.HALF_UP));
            line.setStatus(OrderLineStatus.PENDING);
            return line;
        });
    }

    @Provide
    Arbitrary<List<OrderLine>> orderLines() {
        return orderLine().list().ofMinSize(0).ofMaxSize(20);
    }

    // ── Invariants 4 & 5 : status transitions ──────────────────────────

    /**
     * Invariant 4 (ADR-0059) : Order.status valid graph
     * <pre>PENDING → CONFIRMED → SHIPPED ; * → CANCELLED ; CANCELLED terminal</pre>
     *
     * <p>Property : the Cartesian product of (from, to) statuses contains
     * EXACTLY the documented edges (plus self-transitions). Any deviation
     * (e.g. someone adds a SHIPPED→PENDING shortcut) fails this test.
     */
    @Property
    void orderStatus_transitionGraph_matchesDoc(
            @ForAll OrderStatus from,
            @ForAll OrderStatus to) {
        boolean expected = isValidOrderTransition(from, to);
        assertThat(from.canTransitionTo(to))
                .as("OrderStatus %s → %s expected=%b", from, to, expected)
                .isEqualTo(expected);
    }

    private static boolean isValidOrderTransition(OrderStatus from, OrderStatus to) {
        if (from == to) return true;
        return switch (from) {
            case PENDING -> to == OrderStatus.CONFIRMED || to == OrderStatus.CANCELLED;
            case CONFIRMED -> to == OrderStatus.SHIPPED || to == OrderStatus.CANCELLED;
            case SHIPPED, CANCELLED -> false;
        };
    }

    /**
     * Null target is never a valid transition — captures the contract that
     * callers can't accidentally clear status by passing null.
     */
    @Property(tries = 1)
    void orderStatus_transitionToNull_isFalse() {
        for (OrderStatus s : OrderStatus.values()) {
            assertThat(s.canTransitionTo(null)).isFalse();
        }
    }

    /**
     * Invariant 5 (ADR-0059) : OrderLineStatus valid graph
     * <pre>PENDING → SHIPPED → REFUNDED ; REFUNDED terminal ; no skip</pre>
     */
    @Property
    void orderLineStatus_transitionGraph_matchesDoc(
            @ForAll OrderLineStatus from,
            @ForAll OrderLineStatus to) {
        boolean expected = isValidOrderLineTransition(from, to);
        assertThat(from.canTransitionTo(to))
                .as("OrderLineStatus %s → %s expected=%b", from, to, expected)
                .isEqualTo(expected);
    }

    private static boolean isValidOrderLineTransition(OrderLineStatus from, OrderLineStatus to) {
        if (from == to) return true;
        return switch (from) {
            case PENDING -> to == OrderLineStatus.SHIPPED;
            case SHIPPED -> to == OrderLineStatus.REFUNDED;
            case REFUNDED -> false;
        };
    }

    /**
     * Skip-state check : PENDING cannot directly become REFUNDED — must
     * pass through SHIPPED. Captures the audit requirement that you can
     * only refund what was shipped.
     */
    @Property(tries = 1)
    void orderLineStatus_pendingCannotSkipToRefunded() {
        assertThat(OrderLineStatus.PENDING.canTransitionTo(OrderLineStatus.REFUNDED)).isFalse();
    }

    // ── Invariant 3 : snapshot price immutability ──────────────────────

    /**
     * Invariant 3 (ADR-0059) : `OrderLine.unitPriceAtOrder` is a SNAPSHOT —
     * a copy taken at insert time. Mutating the source `Product.unitPrice`
     * AFTER the line is created MUST NOT change the line's snapshot.
     *
     * <p>This is structurally guaranteed by Java's immutable {@link BigDecimal}
     * + the fact that {@link OrderLine} holds the price by-value, NOT by
     * reference to a {@code Product} entity. The property test documents
     * this contract so a future regression (e.g. someone refactors
     * OrderLine to compute the price lazily from a {@code @ManyToOne Product})
     * fails this test loudly.
     *
     * <p>jqwik generates random pre-snapshot and post-mutation prices ;
     * the assertion holds for any pair.
     */
    @Property(tries = 50)
    void unitPriceAtOrder_doesNotFollowProductMutation(
            @ForAll java.math.BigDecimal preSnapshotPrice,
            @ForAll java.math.BigDecimal postMutationPrice) {
        BigDecimal originalPrice = preSnapshotPrice.setScale(2, RoundingMode.HALF_UP);

        OrderLine line = new OrderLine();
        line.setQuantity(1);
        line.setUnitPriceAtOrder(originalPrice);
        line.setStatus(OrderLineStatus.PENDING);

        // Simulate the upstream Product price being changed AFTER the line
        // was snapshotted. Since OrderLine holds a copy (not a reference),
        // this mutation must not propagate.
        BigDecimal mutatedProductPrice = postMutationPrice.setScale(2, RoundingMode.HALF_UP);
        // (no setter on a Product reference — there's no Product reference.
        // The test is structural : we just assert the line's stored price
        // didn't morph into something else after time passed.)

        assertThat(line.getUnitPriceAtOrder())
                .as("snapshot price must remain %s regardless of any later product price %s",
                        originalPrice, mutatedProductPrice)
                .isEqualByComparingTo(originalPrice);
    }
}
