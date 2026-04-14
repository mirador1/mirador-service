package com.example.customerservice.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Validated request body for {@code POST /customers}.
 *
 * <p>Java records work seamlessly with Bean Validation (JSR-380): annotations placed on
 * record components are applied to the generated constructor parameters, which Hibernate
 * Validator then validates when {@code @Valid} is present on the controller method parameter.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>{@code @NotBlank} — rejects {@code null}, empty, and whitespace-only strings.</li>
 *   <li>{@code @Email} — validates that the email has a syntactically valid format.</li>
 * </ul>
 *
 * <p>Validation failures throw {@code MethodArgumentNotValidException}, which is caught in
 * {@link com.example.customerservice.api.ApiExceptionHandler} and returned as HTTP 400 Problem Detail.
 */
public record CreateCustomerRequest(
        @NotBlank @Size(max = 255, message = "Name must not exceed 255 characters") String name,
        @NotBlank @Email @Size(max = 255, message = "Email must not exceed 255 characters") String email
) {
}
