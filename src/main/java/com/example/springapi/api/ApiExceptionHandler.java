package com.example.springapi.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handle(Exception ex) {
        return switch (ex) {
            case MethodArgumentNotValidException e ->
                    ResponseEntity.badRequest()
                            .body(new ApiError("VALIDATION_ERROR", "Requête invalide"));
            case ConstraintViolationException e ->
                    ResponseEntity.badRequest()
                            .body(new ApiError("CONSTRAINT_VIOLATION", e.getMessage()));
            case IllegalArgumentException e ->
                    ResponseEntity.badRequest()
                            .body(new ApiError("BAD_REQUEST", e.getMessage()));
            case NoSuchElementException e ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .body(new ApiError("NOT_FOUND", e.getMessage()));
            case IllegalStateException e when e.getCause() instanceof TimeoutException ->
                    ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                            .body(new ApiError("KAFKA_TIMEOUT", "Kafka reply timed out"));
            default ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ApiError("INTERNAL_ERROR", "Erreur interne"));
        };
    }
}
