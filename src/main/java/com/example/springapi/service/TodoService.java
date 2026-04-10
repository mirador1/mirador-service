package com.example.springapi.service;

import com.example.springapi.client.JsonPlaceholderClient;
import com.example.springapi.client.TodoItem;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);
    private static final String CIRCUIT_BREAKER_NAME = "jsonplaceholder";

    private final JsonPlaceholderClient client;

    public TodoService(JsonPlaceholderClient client) {
        this.client = client;
    }

    @CircuitBreaker(name = CIRCUIT_BREAKER_NAME, fallbackMethod = "fallbackTodos")
    @Retry(name = CIRCUIT_BREAKER_NAME)
    public List<TodoItem> getTodos(long customerId) {
        return client.getTodos(customerId);
    }

    // Fallback: called when circuit is open or all retries exhausted
    List<TodoItem> fallbackTodos(long customerId, Throwable t) {
        log.warn("todos_fallback customerId={} cause={}", customerId, t.getMessage());
        return List.of();
    }
}
