package com.mirador.observability;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the optional Keycloak health indicator.
 * Guard-condition branches (no network calls) are fully testable without mocks.
 */
class KeycloakHealthIndicatorTest {

    /**
     * Three guard conditions collapse into one parameterised test (Sonar S5976):
     * null issuer URI, blank issuer URI, or a URI without an {@code http(s)} scheme.
     * All three must short-circuit into {@link Status#UNKNOWN} without attempting a
     * network call — the invalid-scheme case also covers
     * {@code "/realms/customer-service"} (a relative URI that happens when
     * {@code KEYCLOAK_URL} is unset and the default expands to the suffix only).
     */
    @ParameterizedTest(name = "[{index}] issuerUri={0} → UNKNOWN")
    @NullSource
    @ValueSource(strings = {
            "   ",
            "/realms/customer-service",
            "ftp://keycloak.example.com/realms/test"
    })
    void guardConditions_returnUnknown(String issuerUri) {
        var indicator = new KeycloakHealthIndicator(issuerUri);
        Health h = indicator.health();
        assertThat(h.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void nullIssuerUri_exposesReasonDetail() {
        // The nullIssuerUri branch is the only one that emits a "reason" detail
        // (the others short-circuit in validation) — keep this as its own assertion.
        var indicator = new KeycloakHealthIndicator(null);
        Health h = indicator.health();
        assertThat(h.getDetails()).containsKey("reason");
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
