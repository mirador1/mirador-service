package com.mirador.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.Collection;

/**
 * Provides the optional Keycloak {@link JwtDecoder} bean.
 *
 * <p>Extracted from {@link SecurityConfig} into its own {@code @Configuration} class to break
 * a circular dependency: {@link SecurityConfig} injects {@link JwtAuthenticationFilter}, which
 * injects {@link JwtDecoder}. With both beans defined in {@link SecurityConfig}, Spring detects
 * a circular reference. Moving the decoder to this class resolves the cycle.
 *
 * <p>Returns {@code null} when {@code KEYCLOAK_URL} is not set, allowing
 * {@link JwtAuthenticationFilter} to use {@code @Nullable JwtDecoder} and skip Keycloak
 * validation in simple JWT-only mode.
 *
 * [Spring Security / Spring Boot 4] — JWKS-backed {@link NimbusJwtDecoder}.
 */
@Configuration
public class KeycloakConfig {

    @Value("${keycloak.issuer-uri:}")
    private String keycloakIssuerUri;

    // Auth0 API identifier — used as the expected JWT audience claim.
    // Auth0 access tokens include aud=[<API_IDENTIFIER>, <AUTH0_DOMAIN>/userinfo].
    // When not configured (local/Keycloak mode), audience validation is skipped.
    @Value("${auth0.audience:}")
    private String auth0Audience;

    /**
     * Keycloak JWKS decoder. Validates token signatures against Keycloak's JWKS endpoint.
     *
     * <p>Uses {@link NimbusJwtDecoder#withJwkSetUri} to avoid an HTTP OIDC discovery call at
     * context startup — JWKS are fetched lazily on the first token validation.
     * Issuer claim validation is added explicitly via
     * {@link JwtValidators#createDefaultWithIssuer(String)}.
     *
     * @return a {@link JwtDecoder} configured for the Keycloak realm, or {@code null} when
     *         Keycloak is not configured
     */
    @Bean
    @Nullable
    public JwtDecoder jwtDecoder() {
        if (keycloakIssuerUri.isBlank()) {
            return null;
        }
        // Keycloak JWKS path is non-standard (/protocol/openid-connect/certs).
        // Auth0 and other OIDC providers expose JWKS at the standard /.well-known/jwks.json.
        // Detect provider by the Keycloak-specific /realms/ segment in the issuer URI.
        boolean isKeycloak = keycloakIssuerUri.contains("/realms/");
        String jwksUri = isKeycloak
                ? keycloakIssuerUri + "/protocol/openid-connect/certs"   // Keycloak
                : keycloakIssuerUri + ".well-known/jwks.json";            // Auth0 / standard OIDC
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();

        // Start with the standard issuer + expiry + not-before validators
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(keycloakIssuerUri);

        // For Auth0: also validate the 'aud' claim to reject tokens issued for other APIs.
        // Auth0 access tokens carry aud = ["https://mirador-api", "https://<domain>/userinfo"].
        // Without audience validation, any Auth0 token from the same tenant would be accepted —
        // even tokens meant for a different API hosted in the same Auth0 organization.
        if (!isKeycloak && !auth0Audience.isBlank()) {
            OAuth2TokenValidator<Jwt> audienceValidator =
                    new JwtClaimValidator<>(
                            JwtClaimNames.AUD,
                            (Collection<String> aud) -> aud != null && aud.contains(auth0Audience)
                    );
            validator = new DelegatingOAuth2TokenValidator<>(validator, audienceValidator);
        }

        decoder.setJwtValidator(validator);
        return decoder;
    }
}
