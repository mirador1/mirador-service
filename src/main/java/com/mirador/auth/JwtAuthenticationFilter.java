package com.mirador.auth;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 *       Validated by HMAC-SHA256 signature check. The role ({@code ROLE_ADMIN},
 *       {@code ROLE_USER}, or {@code ROLE_READER}) is read directly from the {@code role}
 *       claim embedded in the token at issuance.</li>
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
                // Reject tokens that have been explicitly invalidated via POST /auth/logout
                if (jwtTokenProvider.isBlacklisted(token)) {
                    log.debug("JWT token has been blacklisted (user logged out)");
                    filterChain.doFilter(request, response);
                    return;
                }
                authenticateBuiltin(token);
            } else if (keycloakJwtDecoder != null) {
                // Fall back to external JWT (JWKS-validated — Keycloak OR Auth0 OIDC)
                authenticateExternalJwt(token);
            }
        }

        // After authentication: tag the current OTel span with the username and propagate
        // it downstream via W3C Baggage so all child spans (DB, Kafka, HTTP) carry the identity.
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            String name = auth.getName();
            // Tag the current span so the username appears in Grafana Tempo trace details
            Span current = tracer.currentSpan();
            if (current != null) {
                current.tag("user.name", name);
            }
            // BaggageInScope propagates "user.name" as a W3C baggage entry across service boundaries.
            // try-with-resources guarantees the scope is closed after the entire filter chain completes.
            // [OpenTelemetry / Micrometer Tracing — baggage propagation]
            // try-with-resources scope is only held to close the baggage entry
            // after the downstream chain completes — the handle itself is unused.
            // Use `ignored` (not Java 25's `_` unnamed pattern) so SB3+J21 builds
            // compile — underscore is preview in J21, stable only from J22+.
            try (var ignored = tracer.createBaggageInScope("user.name", name)) {
                filterChain.doFilter(request, response);
            }
            return;
        }
        // Unauthenticated requests continue without baggage
        filterChain.doFilter(request, response);
    }

    /**
     * Populates the SecurityContext from a built-in JWT issued by {@link JwtTokenProvider}.
     *
     * <p>The role is read from the {@code role} claim embedded in the token at issuance
     * (one of {@code ROLE_ADMIN}, {@code ROLE_USER}, {@code ROLE_READER}). This avoids
     * the previous anti-pattern of granting every authenticated user both ROLE_USER and
     * ROLE_ADMIN regardless of their actual privilege level.
     */
    private void authenticateBuiltin(String token) {
        String username = jwtTokenProvider.getUsername(token);
        // Read the role directly from the token claim — do not re-derive from username
        String role = jwtTokenProvider.getRole(token);
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                username,
                null,
                List.of(new SimpleGrantedAuthority(role))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        log.debug("Authenticated user '{}' via built-in JWT with role {}", username, role);
    }

    /**
     * Populates the SecurityContext from an external JWT (Keycloak realm or Auth0 tenant).
     *
     * <p>"External" = not issued by our built-in {@link JwtTokenProvider}. Both Keycloak and
     * Auth0 tokens are validated by the same Spring-configured {@link JwtDecoder} bean
     * (JWKS signature check); only the role-claim shape differs.
     *
     * <h3>Role extraction strategy (3 shapes, first-match wins)</h3>
     * <ul>
     *   <li><b>Strategy 1 — Keycloak</b>: roles come from {@code realm_access.roles}
     *       (nested claim). The {@code ROLE_} prefix is already included in the realm
     *       role names.</li>
     *   <li><b>Strategy 2 — Auth0 with RBAC</b>: roles come from a custom namespace
     *       claim {@code https://mirador-api/roles} (populated by an Auth0 Action /
     *       Post-Login rule). Same {@code ROLE_} prefix convention as built-in tokens.</li>
     *   <li><b>Strategy 3 — Auth0 without RBAC</b>: default to {@code ROLE_USER} so the
     *       tenant can authenticate even before roles are provisioned. Temporary —
     *       delete once Auth0 RBAC is in place on all envs.</li>
     * </ul>
     *
     * <p>Historical note: this method used to be named {@code authenticateKeycloak}
     * back when only Keycloak was in scope. Renamed 2026-04-22 after the Clean Code
     * audit flagged the name as misleading (strategies 2 + 3 cover Auth0, not Keycloak).
     *
     * [Spring Security / Spring Boot 4] — {@link JwtDecoder} bean, JWKS validation.
     */
    @SuppressWarnings("unchecked")
    private void authenticateExternalJwt(String token) {
        // Precondition: caller (doFilterInternal) already guards keycloakJwtDecoder != null.
        // This explicit check makes the contract visible to static analysis tools.
        if (keycloakJwtDecoder == null) {
            return;
        }
        try {
            Jwt jwt = keycloakJwtDecoder.decode(token);
            List<GrantedAuthority> authorities = new ArrayList<>();

            // Strategy 1: Keycloak — roles in realm_access.roles (e.g. "ROLE_ADMIN")
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

            // Strategy 2: Auth0 — roles in custom namespace claim https://mirador-api/roles.
            // Populated by an Auth0 Action that adds user roles to the access token.
            // Format: ["ROLE_ADMIN", "ROLE_USER"] (same ROLE_ prefix as built-in tokens).
            if (authorities.isEmpty()) {
                List<?> auth0Roles = jwt.getClaimAsStringList("https://mirador-api/roles");
                if (auth0Roles != null) {
                    auth0Roles.stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .map(SimpleGrantedAuthority::new)
                            .forEach(authorities::add);
                }
            }

            // Strategy 3: Auth0 without RBAC configured — grant ROLE_USER as default.
            // This allows any Auth0-authenticated user to read/write but not delete.
            // Follow-up: once Auth0 RBAC is provisioned (Dashboard → User Management → Roles)
            // and an Auth0 Action embeds the roles in the access token, this fallback
            // becomes dead code and can be removed.
            if (authorities.isEmpty()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                log.debug("No role claims found in external JWT for '{}' — defaulting to ROLE_USER",
                        jwt.getSubject());
            }

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    jwt.getSubject(), null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authenticated subject '{}' via external JWT with roles {}",
                    jwt.getSubject(), authorities);
        } catch (JwtException e) {
            // Token is invalid (bad signature, expired, wrong issuer, etc.) — log at debug,
            // do not set SecurityContext, let Spring Security reject with 401/403 downstream
            log.debug("External JWT validation failed: {}", e.getMessage());
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
