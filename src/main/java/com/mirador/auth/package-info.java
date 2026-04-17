/**
 * Authentication and authorization.
 *
 * <p>Three auth mechanisms coexist, each with its own filter in the Spring
 * Security filter chain. They are tried in order; the first one that
 * authenticates wins:
 *
 * <ol>
 *   <li><b>JWT (local-issued)</b> — {@link JwtAuthenticationFilter} validates
 *       the {@code Authorization: Bearer} header against
 *       {@link JwtTokenProvider} (HMAC-SHA256 with {@code JWT_SECRET}).
 *       Used by the SPA once the user has logged in via {@link AuthController}.</li>
 *
 *   <li><b>OIDC / Auth0 / Keycloak</b> — same filter, but the JWT is validated
 *       against the remote issuer's JWKS when the {@code iss} claim points to
 *       Auth0 or Keycloak. No secret required; the public key is rotated
 *       automatically. Audience validation is enforced in
 *       {@link com.mirador.auth.KeycloakConfig}.</li>
 *
 *   <li><b>API key</b> — {@link ApiKeyAuthenticationFilter} accepts the
 *       {@code X-API-Key} header and grants ROLE_USER + ROLE_ADMIN when the
 *       value matches the configured {@code app.api-key}. Intended for
 *       machine-to-machine calls; scoped per-caller keys are tracked as a follow-up.</li>
 * </ol>
 *
 * <p>Local user storage uses JPA ({@link AppUser} + {@link AppUserRepository}),
 * loaded by {@link AppUserDetailsService}. {@link DataInitializer} seeds a
 * demo admin + user on startup (dev only).
 *
 * <h2>Key collaborators outside this package</h2>
 * <ul>
 *   <li>{@code SecurityConfig} (loaded from {@code com.mirador.auth.SecurityConfig}) — wires the filter chain and CORS.</li>
 *   <li>{@link com.mirador.observability.AuditService} — records login attempts, token refreshes, and admin actions.</li>
 *   <li>{@link com.mirador.resilience.RateLimitingFilter} — runs before authentication so the token bucket drains on anonymous traffic too.</li>
 * </ul>
 */
package com.mirador.auth;
