package com.mirador.observability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the optional Keycloak health indicator.
 * Guard-condition branches (no network calls) are fully testable without mocks.
 */
class KeycloakHealthIndicatorTest {

    @Test
    void nullIssuerUri_returnsUnknown() {
        var indicator = new KeycloakHealthIndicator(null);
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(h.getDetails()).containsKey("reason");
    }

    @Test
    void blankIssuerUri_returnsUnknown() {
        var indicator = new KeycloakHealthIndicator("   ");
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void relativeUri_noScheme_returnsUnknown() {
        // Happens when KEYCLOAK_URL env is not set: ${KEYCLOAK_URL:}/realms/...
        var indicator = new KeycloakHealthIndicator("/realms/customer-service");
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void invalidScheme_returnsUnknown() {
        var indicator = new KeycloakHealthIndicator("ftp://keycloak.example.com/realms/test");
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void unreachableServer_returnsDown() {
        // Port 1 is always refused (privileged, never bound in tests)
        var indicator = new KeycloakHealthIndicator("http://127.0.0.1:1/realms/test");
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
        assertThat(h.getDetails()).containsKey("issuerUri");
    }
}
