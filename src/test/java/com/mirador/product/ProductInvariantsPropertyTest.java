package com.mirador.product;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Negative;
import net.jqwik.api.constraints.Positive;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for invariant 2 of shared ADR-0059 :
 * <pre>Product.stockQuantity >= 0   AND   Product.unitPrice > 0</pre>
 *
 * <p>The {@link CreateProductRequest} DTO carries Bean Validation annotations
 * ({@code @PositiveOrZero}, {@code @NotNull}). Spring's request handling
 * triggers validation before the controller method runs, so any negative
 * stock or null required field gets a 400 response BEFORE any DB write
 * happens. The DB CHECK constraint is the second line of defence.
 *
 * <p>This test confirms the boundary by feeding random ints into the
 * Bean Validator + asserting the violation set matches the expectation.
 *
 * @see <a href="https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0059-customer-order-product-data-model.md">ADR-0059</a>
 */
class ProductInvariantsPropertyTest {

    private final Validator validator = buildValidator();

    private static Validator buildValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            return factory.getValidator();
        }
    }

    /**
     * Invariant 2 (ADR-0059) : non-negative stock_quantity is accepted.
     * Hypothesis-style coverage : full positive int range, no value should
     * trigger a violation against the {@code @PositiveOrZero} constraint
     * on stockQuantity.
     */
    @Property(tries = 100)
    void positiveOrZeroStock_isAccepted(@ForAll @IntRange(min = 0, max = 1_000_000) int stock) {
        CreateProductRequest req = new CreateProductRequest(
                "Widget", null, new BigDecimal("9.99"), stock);

        Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(req);
        assertThat(violations)
                .as("stock=%d is non-negative — should pass validation", stock)
                .isEmpty();
    }

    /**
     * Invariant 2 contrapositive : ANY negative integer triggers a violation.
     * jqwik shrinks failures to the smallest negative int that slips through ;
     * if the Bean Validation annotation is ever weakened, this test fails.
     */
    @Property(tries = 100)
    void negativeStock_isRejected(@ForAll @Negative int stock) {
        CreateProductRequest req = new CreateProductRequest(
                "Widget", null, new BigDecimal("9.99"), stock);

        Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(req);
        assertThat(violations)
                .as("stock=%d is negative — must produce a stockQuantity violation", stock)
                .isNotEmpty()
                .anyMatch(v -> v.getPropertyPath().toString().equals("stockQuantity"));
    }

    /**
     * Same guarantee for {@code unitPrice} : non-negative {@link BigDecimal}
     * accepted ; negative rejected. The DTO enforces {@code @PositiveOrZero}
     * (allowing 0 — actual business rule "price > 0" is enforced by the
     * router/repo layer, not the DTO).
     */
    @Property(tries = 50)
    void nonNegativeUnitPrice_isAccepted(@ForAll @Positive BigDecimal price) {
        CreateProductRequest req = new CreateProductRequest(
                "Widget", null, price, 10);

        Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(req);
        assertThat(violations)
                .as("price=%s is positive — should pass validation", price)
                .isEmpty();
    }

    /**
     * Negative {@link BigDecimal} unit_price is rejected. Generators random
     * across the whole BigDecimal space limited to negative values.
     */
    @Property(tries = 50)
    void negativeUnitPrice_isRejected(@ForAll @Negative BigDecimal price) {
        CreateProductRequest req = new CreateProductRequest(
                "Widget", null, price, 10);

        Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(req);
        assertThat(violations)
                .as("price=%s is negative — must produce a unitPrice violation", price)
                .isNotEmpty()
                .anyMatch(v -> v.getPropertyPath().toString().equals("unitPrice"));
    }

    /**
     * Boundary : stockQuantity = 0 is explicitly allowed (out-of-stock
     * products are still cataloguable). This is example-based by design —
     * the boundary value matters more than random sampling here.
     */
    @Property(tries = 1)
    void zeroStock_isExplicitlyAllowed() {
        CreateProductRequest req = new CreateProductRequest(
                "Out of stock", null, new BigDecimal("9.99"), 0);

        Set<ConstraintViolation<CreateProductRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
