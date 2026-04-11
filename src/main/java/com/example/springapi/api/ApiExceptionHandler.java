package com.example.springapi.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ProblemDetail handle(Exception ex) {
        return switch (ex) {
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
