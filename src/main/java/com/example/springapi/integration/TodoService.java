package com.example.springapi.integration;

import com.example.springapi.integration.JsonPlaceholderClient;
import com.example.springapi.integration.TodoItem;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Fetches todos from the external JSONPlaceholder API with Resilience4j protection.
 *
 * <p>Two Resilience4j mechanisms are stacked on {@link #getTodos}:
 * <ol>
 *   <li><b>Retry</b> — retries the call up to N times (configured under
 *       {@code resilience4j.retry.instances.jsonplaceholder}) with exponential backoff.
 *       Transient network errors or 5xx responses are retried automatically.</li>
 *   <li><b>Circuit Breaker</b> — tracks the failure rate over a sliding window.
 *       When the failure rate exceeds the threshold (default 50%), the circuit opens and
 *       subsequent calls are short-circuited immediately (no network attempt) for a
 *       configurable wait duration. This prevents cascading failures when a downstream
 *       service is degraded.</li>
 * </ol>
 *
 * <p>Both annotations reference the same instance name ({@code jsonplaceholder}) so they share
 * configuration and metrics. Resilience4j metrics are automatically exported to Micrometer
 * via the {@code resilience4j-micrometer} module bundled in
 * {@code resilience4j-spring-boot3}, exposing circuit state and call counts in Prometheus.
 *
 * <p>The {@link #fallbackTodos} method is the last resort: it is called when the circuit is
 * open OR when all retry attempts are exhausted. It returns an empty list so the caller
 * (the REST endpoint) can still return a valid 200 response with degraded data.
 */
@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);
    // Resilience4j instance name — must match keys in application.yml under resilience4j.*
    private static final String CIRCUIT_BREAKER_NAME = "jsonplaceholder";

    private final JsonPlaceholderClient client;

    public TodoService(JsonPlaceholderClient client) {
        this.client = client;
    }

    /**
     * Calls the external API. The {@code @Retry} is applied first (inner decorator),
     * then {@code @CircuitBreaker} (outer decorator) — meaning the circuit breaker
     * counts a failure only after all retries are exhausted.
     */
    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "fallbackTodos")
    @Retry(name = CIRCUIT_BREAKER_NAME)
    public List<TodoItem> getTodos(long customerId) {
        return client.getTodos(customerId);
    }

    /**
     * Fallback invoked when the circuit is open or all retries fail.
     * The method signature must match {@link #getTodos} with an additional {@code Throwable} parameter.
     * Returns an empty list for graceful degradation.
     */
    List<TodoItem> fallbackTodos(long customerId, Throwable t) {
        log.warn("todos_fallback customerId={} cause={}", customerId, t.getMessage());
        return List.of();
    }
}
