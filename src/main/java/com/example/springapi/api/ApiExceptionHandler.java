package com.example.springapi.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/**
 * Global exception handler that translates Java exceptions into RFC 7807 Problem Details.
 *
 * <h3>RFC 7807 — Problem Details for HTTP APIs</h3>
 * <p>Instead of returning arbitrary error bodies, this handler uses Spring 6's
 * {@link ProblemDetail} which implements the RFC 7807 standard:
 * <pre>
 * HTTP/1.1 400 Bad Request
 * Content-Type: application/problem+json
 *
 * {
 *   "type": "urn:problem:validation-error",
 *   "title": "Validation Error",
 *   "status": 400,
 *   "detail": "Invalid request"
 * }
 * </pre>
 *
 * <h3>Exception mapping</h3>
 * <p>The handler uses a Java 21 {@code switch} expression with pattern matching to map
 * each exception type to the appropriate HTTP status and problem detail:
 * <ul>
 *   <li>{@code MethodArgumentNotValidException} → 400 (Bean Validation failure on request body)</li>
 *   <li>{@code ConstraintViolationException} → 400 (Bean Validation failure on method parameters)</li>
 *   <li>{@code IllegalArgumentException} → 400 (explicit bad-argument signalling from services)</li>
 *   <li>{@code NoSuchElementException} → 404 (entity not found)</li>
 *   <li>{@code IllegalStateException} wrapping {@code TimeoutException} → 504 (Kafka reply timeout)</li>
 *   <li>Anything else → 500 (unexpected internal error)</li>
 * </ul>
 *
 * <p>{@code @RestControllerAdvice} is a meta-annotation combining {@code @ControllerAdvice}
 * (applies to all controllers) and {@code @ResponseBody} (serializes the return value as JSON).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Catches every exception thrown from controllers and maps it to an appropriate HTTP response.
     *
     * <p>Using a single catch-all handler with pattern matching avoids duplicating the
     * response-writing logic across multiple {@code @ExceptionHandler} methods and keeps
     * the full exception taxonomy in one readable place.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception ex) {
        return switch (ex) { // [Java 21+] pattern matching switch expression
            case MethodArgumentNotValidException e -> {
                var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
                pd.setType(URI.create("urn:problem:validation-error"));
                pd.setTitle("Validation Error");
                pd.setDetail("Invalid request");
                yield pd;
            }
            case ConstraintViolationException e -> {
                var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
                pd.setType(URI.create("urn:problem:constraint-violation"));
                pd.setTitle("Constraint Violation");
                pd.setDetail(e.getMessage());
                yield pd;
            }
            case IllegalArgumentException e -> {
                var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
                pd.setType(URI.create("urn:problem:bad-request"));
                pd.setTitle("Bad Request");
                pd.setDetail(e.getMessage());
                yield pd;
            }
            case NoSuchElementException e -> {
                var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
                pd.setType(URI.create("urn:problem:not-found"));
                pd.setTitle("Not Found");
                pd.setDetail(e.getMessage());
                yield pd;
            }
            case NoResourceFoundException e -> {
                var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
                pd.setType(URI.create("urn:problem:not-found"));
                pd.setTitle("Not Found");
                pd.setDetail(e.getMessage());
                yield pd;
            }
            case IllegalStateException e when e.getCause() instanceof TimeoutException -> {
                var pd = ProblemDetail.forStatus(HttpStatus.GATEWAY_TIMEOUT);
                pd.setType(URI.create("urn:problem:kafka-timeout"));
                pd.setTitle("Kafka Reply Timeout");
                pd.setDetail("Kafka reply timed out");
                yield pd;
            }
            default -> {
                var pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
                pd.setType(URI.create("urn:problem:internal-error"));
                pd.setTitle("Internal Server Error");
                pd.setDetail("An unexpected error occurred");
                yield pd;
            }
        };
    }
}
