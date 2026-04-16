package com.mirador.api;

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
 * <p><b>Java 17 variant</b> — uses if/else chain instead of the Java 21 switch expression
 * with pattern matching ({@code case Type e ->}) which is not available in Java 17.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException) {
            var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
            pd.setType(URI.create("urn:problem:validation-error"));
            pd.setTitle("Validation Error");
            pd.setDetail("Invalid request");
            return pd;
        }
        if (ex instanceof ConstraintViolationException) {
            var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
            pd.setType(URI.create("urn:problem:constraint-violation"));
            pd.setTitle("Constraint Violation");
            pd.setDetail(ex.getMessage());
            return pd;
        }
        if (ex instanceof IllegalArgumentException) {
            var pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
            pd.setType(URI.create("urn:problem:bad-request"));
            pd.setTitle("Bad Request");
            pd.setDetail(ex.getMessage());
            return pd;
        }
        if (ex instanceof NoSuchElementException) {
            var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
            pd.setType(URI.create("urn:problem:not-found"));
            pd.setTitle("Not Found");
            pd.setDetail(ex.getMessage());
            return pd;
        }
        if (ex instanceof NoResourceFoundException) {
            var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
            pd.setType(URI.create("urn:problem:not-found"));
            pd.setTitle("Not Found");
            pd.setDetail(ex.getMessage());
            return pd;
        }
        if (ex instanceof IllegalStateException && ex.getCause() instanceof TimeoutException) {
            var pd = ProblemDetail.forStatus(HttpStatus.GATEWAY_TIMEOUT);
            pd.setType(URI.create("urn:problem:kafka-timeout"));
            pd.setTitle("Kafka Reply Timeout");
            pd.setDetail("Kafka reply timed out");
            return pd;
        }
        var pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setType(URI.create("urn:problem:internal-error"));
        pd.setTitle("Internal Server Error");
        pd.setDetail("An unexpected error occurred");
        return pd;
    }
}
