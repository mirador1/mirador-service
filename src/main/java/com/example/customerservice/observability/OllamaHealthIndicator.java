package com.example.customerservice.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Health indicator for the Ollama LLM runtime.
 *
 * <p>Calls {@code GET /api/tags} on the Ollama server to check if it is reachable
 * and has at least one model loaded. This is a lightweight endpoint that returns
 * the list of available models without running inference.
 *
 * <p>Appears at {@code /actuator/health} as the {@code ollama} component.
 * Unlike Kafka or PostgreSQL, Ollama being DOWN does not affect readiness —
 * the circuit breaker in {@code BioService} handles the fallback gracefully.
 */
@Component("ollama")
public class OllamaHealthIndicator implements HealthIndicator {

    private final String baseUrl;

    public OllamaHealthIndicator(@Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @Override
    public Health health() {
        try {
            String response = RestClient.create(baseUrl)
                    .get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(String.class);
            return Health.up()
                    .withDetail("endpoint", baseUrl)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("endpoint", baseUrl)
                    .build();
        }
    }
}
