package com.example.springapi.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

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
        // Construct JWKS URI directly — avoids the /.well-known/openid-configuration HTTP call at startup
        String jwksUri = keycloakIssuerUri + "/protocol/openid-connect/certs";
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(keycloakIssuerUri));
        return decoder;
    }
}
