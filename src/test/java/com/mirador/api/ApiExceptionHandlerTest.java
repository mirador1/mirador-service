package com.mirador.api;

import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the RFC 7807 global exception handler.
 * No Spring context — direct instantiation is enough for pure mapping logic.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void methodArgumentNotValidException_returns400_validationError() {
        var ex = mock(MethodArgumentNotValidException.class);
        ProblemDetail pd = handler.handle(ex);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getType().toString()).isEqualTo("urn:problem:validation-error");
        assertThat(pd.getTitle()).isEqualTo("Validation Error");
    }

    @Test
    void constraintViolationException_returns400_constraintViolation() {
        var ex = new ConstraintViolationException("size must be < 50", Set.of());
        ProblemDetail pd = handler.handle(ex);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getType().toString()).isEqualTo("urn:problem:constraint-violation");
        assertThat(pd.getDetail()).contains("size must be < 50");
    }

    @Test
    void illegalArgumentException_returns400_badRequest() {
        var ex = new IllegalArgumentException("invalid sort field");
        ProblemDetail pd = handler.handle(ex);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(pd.getType().toString()).isEqualTo("urn:problem:bad-request");
        assertThat(pd.getDetail()).isEqualTo("invalid sort field");
    }

    @Test
    void noSuchElementException_returns404_notFound() {
        var ex = new NoSuchElementException("Customer 99 not found");
        ProblemDetail pd = handler.handle(ex);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getType().toString()).isEqualTo("urn:problem:not-found");
    }

    @Test
    void noResourceFoundException_returns404_notFound() throws Exception {
        var ex = mock(NoResourceFoundException.class);
        ProblemDetail pd = handler.handle(ex);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(pd.getType().toString()).isEqualTo("urn:problem:not-found");
    }

    @Test
    void illegalStateWithTimeoutCause_returns504_kafkaTimeout() {
        var cause = new TimeoutException("reply timed out");
        var ex = new IllegalStateException("Kafka error", cause);
        ProblemDetail pd = handler.handle(ex);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT.value());
        assertThat(pd.getType().toString()).isEqualTo("urn:problem:kafka-timeout");
        assertThat(pd.getTitle()).isEqualTo("Kafka Reply Timeout");
    }

    @Test
    void illegalStateWithoutTimeoutCause_returns500_internalError() {
        // guard pattern: IllegalStateException must wrap TimeoutException specifically
        var ex = new IllegalStateException("some other state issue");
        ProblemDetail pd = handler.handle(ex);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(pd.getType().toString()).isEqualTo("urn:problem:internal-error");
    }

    @Test
    void unknownException_returns500_internalError() {
        var ex = new RuntimeException("surprise");
        ProblemDetail pd = handler.handle(ex);
        assertThat(pd.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(pd.getType().toString()).isEqualTo("urn:problem:internal-error");
        assertThat(pd.getDetail()).isEqualTo("An unexpected error occurred");
    }
}
