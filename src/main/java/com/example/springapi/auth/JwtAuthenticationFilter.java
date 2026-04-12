package com.example.springapi.auth;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring Security filter that validates JWT Bearer tokens on every incoming request.
 *
 * <h3>Dual-mode JWT validation</h3>
 * <p>The filter supports two token types:
 * <ol>
 *   <li><b>Built-in JWT</b> — issued by {@link JwtTokenProvider} via {@code POST /auth/login}.
 *       Validated by HMAC-SHA256 signature check. All authenticated users receive
 *       {@code ROLE_USER} and {@code ROLE_ADMIN} from the token subject.</li>
 *   <li><b>Keycloak JWT</b> — issued by Keycloak's Authorization Server.
 *       Validated by the {@link JwtDecoder} bean (JWKS signature check) when
 *       {@code KEYCLOAK_URL} is set. Roles are extracted from the
 *       {@code realm_access.roles} claim.</li>
 * </ol>
 *
 * <p>The filter tries the built-in validator first. If the token is not a valid
 * built-in JWT (e.g., it's a Keycloak token), it falls through to the Keycloak decoder
 * (when configured). If both fail, the SecurityContext is not populated and Spring
 * Security's authorization rules reject the request with 401/403.
 *
 * <p>This single-filter approach avoids having two separate authentication paths
 * ({@code http.oauth2ResourceServer()} + custom filter), which would cause
 * {@code BearerTokenAuthenticationFilter} to clear a valid SecurityContext set by the
 * custom filter when it fails to validate a built-in token.
 *
 * <h3>Why OncePerRequestFilter?</h3>
 * <p>Guarantees execution exactly once per request regardless of internal forwards.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    /**
     * Optional Keycloak {@link JwtDecoder} — {@code null} when {@code KEYCLOAK_URL} is not set.
     * Injected via {@code @Nullable} to allow the filter to start even in simple JWT-only mode.
     */
    @Nullable
    private final JwtDecoder keycloakJwtDecoder;

    /**
     * Micrometer Tracing abstraction for span tags and W3C Baggage propagation.
     * Auto-configured by Spring Boot when {@code micrometer-tracing-bridge-otel} is on the classpath.
     * [OpenTelemetry / Micrometer Tracing — baggage propagation]
     */
    private final Tracer tracer;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    @Nullable JwtDecoder keycloakJwtDecoder,
                                    Tracer tracer) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.keycloakJwtDecoder = keycloakJwtDecoder;
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token)) {
            // Try built-in JWT first (HMAC-SHA256, issued by /auth/login)
            if (jwtTokenProvider.validateToken(token)) {
                authenticateBuiltin(token);
            } else if (keycloakJwtDecoder != null) {
                // Fall back to Keycloak JWT (JWKS-validated, issued by Keycloak)
                authenticateKeycloak(token);
            }
        }

        // After authentication: tag the current OTel span with the username and propagate
        // it downstream via W3C Baggage so all child spans (DB, Kafka, HTTP) carry the identity.
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String name = auth.getName();
            // Tag the current span so the username appears in Zipkin/Grafana Tempo trace details
            Span current = tracer.currentSpan();
            if (current != null) {
                current.tag("user.name", name);
            }
            // BaggageInScope propagates "user.name" as a W3C baggage entry across service boundaries.
            // try-with-resources guarantees the scope is closed after the entire filter chain completes.
            // [OpenTelemetry / Micrometer Tracing — baggage propagation]
            try (BaggageInScope ignored = tracer.createBaggageInScope("user.name", name)) {
                filterChain.doFilter(request, response);
            }
            return;
        }
        // Unauthenticated requests continue without baggage
        filterChain.doFilter(request, response);
    }

    /**
     * Populates the SecurityContext from a built-in JWT issued by {@link JwtTokenProvider}.
     * All built-in users receive {@code ROLE_USER} and {@code ROLE_ADMIN}.
     */
    private void authenticateBuiltin(String token) {
        String username = jwtTokenProvider.getUsername(token);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.debug("Authenticated user '{}' via built-in JWT", username);
    }

    /**
     * Populates the SecurityContext from a Keycloak-issued JWT.
     *
     * <p>Roles are extracted from the {@code realm_access.roles} claim, which Keycloak
     * stores as a nested object: {@code {"realm_access": {"roles": ["ROLE_USER", "ROLE_ADMIN"]}}}.
     * Each role string becomes a {@link SimpleGrantedAuthority} directly (the {@code ROLE_}
     * prefix is already present in the Keycloak realm configuration).
     *
     * [Spring Security / Spring Boot 4] — {@link JwtDecoder} bean, JWKS validation.
     */
    @SuppressWarnings("unchecked")
    private void authenticateKeycloak(String token) {
        // Precondition: caller (doFilterInternal) already guards keycloakJwtDecoder != null.
        // This explicit check makes the contract visible to static analysis tools.
        if (keycloakJwtDecoder == null) {
            return;
        }
        try {
            Jwt jwt = keycloakJwtDecoder.decode(token);
            List<GrantedAuthority> authorities = new ArrayList<>();

            // Extract roles from realm_access.roles (Keycloak's nested claim structure)
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null) {
                Object rolesObj = realmAccess.get("roles");
                if (rolesObj instanceof List<?> roles) {
                    roles.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .map(SimpleGrantedAuthority::new)
                            .forEach(authorities::add);
                }
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    jwt.getSubject(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authenticated subject '{}' via Keycloak JWT with roles {}",
                    jwt.getSubject(), authorities);
        } catch (JwtException e) {
            // Token is invalid (bad signature, expired, wrong issuer, etc.) — log at debug,
            // do not set SecurityContext, let Spring Security reject with 401/403 downstream
            log.debug("Keycloak JWT validation failed: {}", e.getMessage());
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
