package com.mirador.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Optional health indicator for Keycloak OAuth2 server.
 *
 * <p>Only active when {@code keycloak.issuer-uri} is set (non-empty).
 * This avoids a startup failure when running without Keycloak (e.g., local dev
 * with JWT-only auth).
 *
 * <p>Calls {@code GET <issuer-uri>} — Keycloak returns realm metadata as JSON
 * when the realm endpoint is reachable. This confirms:
 * <ul>
 *   <li>Keycloak is running and the realm is accessible.</li>
 *   <li>The network path from the app to Keycloak is open.</li>
 * </ul>
 *
 * <p>Appears at {@code /actuator/health} as the {@code keycloak} component.
 * Keycloak being DOWN does NOT affect the readiness group — the app continues
 * to work with local JWT authentication even when Keycloak is unavailable.
 *
 * <h3>Diagnostic scenario</h3>
 * <p>{@code docker compose stop keycloak} → poll {@code GET /actuator/health}
 * to see the keycloak component transition from UP to DOWN.
 */
@Component("keycloak")
@ConditionalOnProperty(name = "keycloak.issuer-uri", matchIfMissing = false)
public class KeycloakHealthIndicator implements HealthIndicator {

    private final String issuerUri;

    public KeycloakHealthIndicator(
            @Value("${keycloak.issuer-uri}") String issuerUri) {
        this.issuerUri = issuerUri;
    }

    @Override
    public Health health() {
        if (issuerUri == null || issuerUri.isBlank()) {
            return Health.unknown()
                    .withDetail("reason", "keycloak.issuer-uri not configured")
                    .build();
        }
        try {
            RestClient.create(issuerUri)
                    .get()
                    .retrieve()
                    .body(String.class);
            return Health.up()
                    .withDetail("issuerUri", issuerUri)
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("issuerUri", issuerUri)
                    .build();
        }
    }
}
