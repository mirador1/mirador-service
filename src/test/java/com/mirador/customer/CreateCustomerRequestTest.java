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
 * Unit tests for {@link CreateCustomerRequest} validation rules.
 *
 * <p>The record is used by both {@code POST /customers} and
 * {@code PUT /customers/{id}}; a regression in any of its
 * {@code @NotBlank} / {@code @Email} / {@code @Size} annotations would
 * silently let invalid payloads through (or vice versa, reject
 * legitimate ones). Tests pin every constraint.
 *
 * <p>Uses Hibernate Validator directly so no Spring context is needed.
 */
class CreateCustomerRequestTest {

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
    void validRequest_passesAllConstraints() {
        var req = new CreateCustomerRequest("Alice Martin", "alice@example.com");

        Set<ConstraintViolation<CreateCustomerRequest>> violations = validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    void blankName_failsNotBlank() {
        var req = new CreateCustomerRequest("", "alice@example.com");

        Set<ConstraintViolation<CreateCustomerRequest>> violations = validator.validate(req);

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .contains("name");
    }

    @Test
    void whitespaceOnlyName_failsNotBlank() {
        // @NotBlank semantics: rejects whitespace-only strings (vs @NotEmpty).
        var req = new CreateCustomerRequest("   ", "alice@example.com");

        assertThat(validator.validate(req)).extracting(v -> v.getPropertyPath().toString())
                .contains("name");
    }

    @Test
    void nullName_failsNotBlank() {
        var req = new CreateCustomerRequest(null, "alice@example.com");

        assertThat(validator.validate(req)).extracting(v -> v.getPropertyPath().toString())
                .contains("name");
    }

    @Test
    void nameLongerThan255_failsSize() {
        var req = new CreateCustomerRequest("a".repeat(256), "alice@example.com");

        var violations = validator.validate(req);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void exactlyMaxLengthName_passes() {
        // Boundary check — 255 chars is the inclusive maximum.
        var req = new CreateCustomerRequest("a".repeat(255), "alice@example.com");

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void invalidEmailFormat_failsEmail() {
        var req = new CreateCustomerRequest("Alice", "not-an-email");

        assertThat(validator.validate(req)).extracting(v -> v.getPropertyPath().toString())
                .contains("email");
    }

    @Test
    void blankEmail_failsNotBlankBeforeFormat() {
        var req = new CreateCustomerRequest("Alice", "");

        assertThat(validator.validate(req)).extracting(v -> v.getPropertyPath().toString())
                .contains("email");
    }

    @Test
    void emailLongerThan255_failsSize() {
        // Construct a syntactically valid but oversize email: 250-char local
        // + "@x.co" → 255+ chars total.
        var req = new CreateCustomerRequest("Alice", "a".repeat(250) + "@x.com");

        var violations = validator.validate(req);
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Test
    void unicodeNameAccepted() {
        // Names contain accents (é, ç, …) and CJK characters — the validator
        // must NOT reject them. Pinned to guard against any future "ASCII
        // only" tightening.
        var req = new CreateCustomerRequest("José Müller — 山田", "jose@example.com");

        assertThat(validator.validate(req)).isEmpty();
    }
}
