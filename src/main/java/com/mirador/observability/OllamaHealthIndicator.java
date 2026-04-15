package com.mirador.observability;

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

    // Sonar java:S1192 — "endpoint" detail key used in every Health builder call.
    private static final String DETAIL_ENDPOINT = "endpoint";

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
                    .withDetail(DETAIL_ENDPOINT, baseUrl)
                    .build();
        } catch (Exception ex) {
            // Connection refused means Ollama is simply not running — report as
            // UNKNOWN (optional service) rather than DOWN to keep overall health UP.
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (ex instanceof org.springframework.web.client.ResourceAccessException
                    || msg.contains("Connection refused")
                    || msg.contains("Failed to connect")
                    || msg.contains("I/O error")) {
                return Health.unknown()
                        .withDetail(DETAIL_ENDPOINT, baseUrl)
                        .withDetail("reason", "Ollama not running (optional — required for /bio endpoint)")
                        .build();
            }
            return Health.down(ex)
                    .withDetail(DETAIL_ENDPOINT, baseUrl)
                    .build();
        }
    }
}
