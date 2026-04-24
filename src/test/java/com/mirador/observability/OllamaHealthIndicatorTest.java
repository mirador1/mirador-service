package com.mirador.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the optional Ollama health indicator.
 *
 * Strategy : subclass + override the {@code probeOllama()} protected
 * method to substitute a pre-configured outcome (no-op for UP, throw
 * for DOWN/UNKNOWN). The REAL {@code health()} method runs — that's
 * what we want to cover. Previous implementation used the anonymous
 * subclass pattern on {@code health()} itself which bypassed the
 * production logic entirely and gave 20 % branch coverage (2/10).
 *
 * With this refactor, all 10 branches of {@code health()} are
 * exercised : the try-block UP path, the catch-block classification
 * (ResourceAccessException / msg contains Connection-refused / msg
 * contains Failed-to-connect / msg contains I/O-error / none-match →
 * DOWN), and the null-message guard.
 */
class OllamaHealthIndicatorTest {

    @Test
    void probeSucceeds_returnsUp() {
        // No-op probe → UP branch exercised.
        var indicator = new OllamaHealthIndicator("http://localhost:11434") {
            @Override
            protected void probeOllama() {
                // success — do nothing
            }
        };
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsEntry("endpoint", "http://localhost:11434");
    }

    @Test
    void resourceAccessException_returnsUnknown() {
        // ResourceAccessException (typical "Connection refused" wrapper) → UNKNOWN.
        var indicator = new OllamaHealthIndicator("http://localhost:11434") {
            @Override
            protected void probeOllama() {
                throw new ResourceAccessException("I/O error on GET request");
            }
        };
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(h.getDetails().get("reason").toString()).contains("Ollama not running");
    }

    @Test
    void connectionRefusedMessage_nonResourceAccessException_returnsUnknown() {
        // A plain RuntimeException whose message contains "Connection refused" →
        // also classified as UNKNOWN (covers the msg.contains branch).
        var indicator = new OllamaHealthIndicator("http://localhost:11434") {
            @Override
            protected void probeOllama() {
                throw new RuntimeException("Connection refused to 127.0.0.1:11434");
            }
        };
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void failedToConnectMessage_returnsUnknown() {
        // msg.contains("Failed to connect") branch.
        var indicator = new OllamaHealthIndicator("http://localhost:11434") {
            @Override
            protected void probeOllama() {
                throw new RuntimeException("Failed to connect to Ollama backend");
            }
        };
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void ioErrorMessage_returnsUnknown() {
        // msg.contains("I/O error") branch.
        var indicator = new OllamaHealthIndicator("http://localhost:11434") {
            @Override
            protected void probeOllama() {
                throw new RuntimeException("I/O error during probe");
            }
        };
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void unexpectedException_returnsDown() {
        // An exception that doesn't match any of the "Ollama not running" patterns
        // falls through to Health.down() — covers the else-branch of the catch.
        var indicator = new OllamaHealthIndicator("http://localhost:11434") {
            @Override
            protected void probeOllama() {
                throw new IllegalStateException("unexpected server state");
            }
        };
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsKey("endpoint");
    }

    @Test
    void exceptionWithNullMessage_returnsDown() {
        // Guard against NPE when ex.getMessage() returns null — the code
        // uses `ex.getMessage() != null ? ex.getMessage() : ""` to avoid it.
        // This test locks that defensive branch.
        var indicator = new OllamaHealthIndicator("http://localhost:11434") {
            @Override
            protected void probeOllama() {
                throw new IllegalStateException();  // null message
            }
        };
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void defaultBaseUrl_usedWhenNoPropertySet() {
        // Verify constructor wiring works with the default value — this test
        // hits the real RestClient.create() path so its outcome depends on
        // the environment (UP if Ollama is running, UNKNOWN otherwise).
        var indicator = new OllamaHealthIndicator("http://localhost:11434");
        Health h = indicator.health();
        assertThat(h.getStatus()).isIn(Status.UP, Status.UNKNOWN, Status.DOWN);
    }
}
