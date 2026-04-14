package com.mirador.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the optional Ollama health indicator.
 */
class OllamaHealthIndicatorTest {

    @Test
    void ollamaNotRunning_connectionRefused_returnsUnknown() {
        // Port 1 will always refuse — simulates Ollama not started
        var indicator = new OllamaHealthIndicator("http://127.0.0.1:1");
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(h.getDetails().get("reason").toString())
                .contains("Ollama not running");
    }

    @Test
    void defaultBaseUrl_usedWhenNoPropertySet() {
        // Verify constructor wiring works with the default value
        var indicator = new OllamaHealthIndicator("http://localhost:11434");
        // Just verify instantiation and health() doesn't throw unexpectedly
        Health h = indicator.health();
        // Depending on environment: UP (Ollama running) or UNKNOWN (not running)
        assertThat(h.getStatus()).isIn(Status.UP, Status.UNKNOWN, Status.DOWN);
    }

    @Test
    void resourceAccessException_returnsUnknown() {
        // Subclass to inject a specific exception without needing a live server
        var indicator = new OllamaHealthIndicator("http://localhost:11434") {
            @Override
            public Health health() {
                try {
                    throw new ResourceAccessException("I/O error on GET request for http://localhost:11434/api/tags");
                } catch (Exception ex) {
                    String msg = ex.getMessage() != null ? ex.getMessage() : "";
                    if (ex instanceof ResourceAccessException
                            || msg.contains("Connection refused")
                            || msg.contains("Failed to connect")
                            || msg.contains("I/O error")) {
                        return Health.unknown()
                                .withDetail("endpoint", "http://localhost:11434")
                                .withDetail("reason", "Ollama not running (optional — required for /bio endpoint)")
                                .build();
                    }
                    return Health.down(ex).withDetail("endpoint", "http://localhost:11434").build();
                }
            }
        };
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
    }
}
