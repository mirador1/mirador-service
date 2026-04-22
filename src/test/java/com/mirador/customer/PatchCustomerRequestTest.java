package com.mirador.customer;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PatchCustomerRequest} validation.
 *
 * <p>The PATCH semantics differ from POST/PUT: every field is OPTIONAL.
 * Tests pin that {@code null} fields are valid (the partial-update
 * contract) but values that ARE provided still pass through their
 * format constraints.
 */
class PatchCustomerRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Test
    void allFieldsNull_isValid() {
        // Pure partial-update contract: an empty PATCH body should validate.
        // The service layer interprets "null = don't change".
        var req = new PatchCustomerRequest(null, null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void onlyNameProvided_isValid() {
        var req = new PatchCustomerRequest("New Name", null);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void onlyEmailProvided_isValid() {
        var req = new PatchCustomerRequest(null, "new@example.com");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void bothFieldsProvided_isValid() {
        var req = new PatchCustomerRequest("New Name", "new@example.com");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void invalidEmail_failsEmail_evenWhenNameOmitted() {
        // Optional ≠ permissive on format. If the field IS provided, it must
        // be valid (otherwise PATCH would let "fix-me" emails through).
        var req = new PatchCustomerRequest(null, "not-an-email");

        Set<ConstraintViolation<PatchCustomerRequest>> violations = validator.validate(req);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("email");
    }

    @Test
    void nameOver255Chars_failsSize() {
        var req = new PatchCustomerRequest("a".repeat(256), null);

        assertThat(validator.validate(req)).extracting(v -> v.getPropertyPath().toString())
                .contains("name");
    }

    @Test
    void emptyStringEmail_isValidByEmailButHandledBySvcLayer() {
        // Edge case: @Email accepts "" as valid (Hibernate Validator
        // convention — null/empty bypass format check). This is intentional
        // — empty string + null are both interpreted as "omit" by the
        // service layer's `if (request.email() != null && !request.email().isBlank())`
        // guard. Test pins the validator behaviour so a future "stricter"
        // change there doesn't silently break PATCH semantics.
        var req = new PatchCustomerRequest(null, "");

        assertThat(validator.validate(req)).isEmpty();
    }
}
