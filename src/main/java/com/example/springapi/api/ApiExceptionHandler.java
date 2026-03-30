package com.example.springapi.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
            default ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ApiError("INTERNAL_ERROR", "Erreur interne"));
        };
    }
}
