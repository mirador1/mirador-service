package com.mirador.api;

/**
 * Simple error response record used as an alternative to {@link org.springframework.http.ProblemDetail}
 * for lightweight error messages.
 *
 * <p>Java records (JEP 395, Java 16+) generate {@code equals}, {@code hashCode},
 * {@code toString}, and a compact canonical constructor automatically, making them
 * ideal for immutable DTOs like error responses.
 *
 * <p>Example JSON output:
 * <pre>
 * { "code": "RATE_LIMIT_EXCEEDED", "message": "Too many requests, retry after 10s" }
 * </pre>
 */
public record ApiError(String code, String message) {
}
