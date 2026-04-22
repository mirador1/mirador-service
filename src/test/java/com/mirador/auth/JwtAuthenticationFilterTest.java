package com.mirador.auth;

import io.micrometer.tracing.BaggageInScope;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JwtAuthenticationFilter} — focuses on the
 * built-in JWT path (HMAC-SHA256, issued by {@link JwtTokenProvider}).
 * The Keycloak/Auth0 external-JWT path is exercised separately via
 * {@code Auth0JwtValidationITest}.
 *
 * <p>Pinned invariants:
 * <ol>
 *   <li>Missing or non-Bearer Authorization header → no auth, chain
 *       continues so non-protected routes still work.</li>
 *   <li>Valid built-in JWT → SecurityContext populated with the role
 *       from the {@code role} claim (NOT re-derived from username).</li>
 *   <li>Blacklisted JWT (post-logout) → no auth even if the signature
 *       is otherwise valid. This is the load-bearing logout enforcement.</li>
 * </ol>
 */
class JwtAuthenticationFilterTest {

    private JwtTokenProvider jwt;
    private Tracer tracer;
    private FilterChain chain;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwt = mock(JwtTokenProvider.class);
        tracer = mock(Tracer.class);
        chain = mock(FilterChain.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);

        // No Keycloak decoder for these tests — pure built-in JWT path.
        filter = new JwtAuthenticationFilter(jwt, null, tracer);

        // Tracer baggage stub — must be lenient because not all tests reach
        // the post-auth baggage block (unauthenticated paths skip it).
        BaggageInScope baggageScope = mock(BaggageInScope.class);
        lenient().when(tracer.createBaggageInScope(anyString(), anyString()))
                .thenReturn(baggageScope);

        // Tracer.currentSpan() can return null (no active span in unit tests) —
        // the filter handles that branch gracefully.
        lenient().when(tracer.currentSpan()).thenReturn(null);

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Missing / malformed Authorization header ──────────────────────────────

    @Test
    void missingAuthorizationHeader_noAuthSetAndChainContinues() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        // Critical: never even tries to validate (no token to validate)
        verify(jwt, never()).validateToken(anyString());
    }

    @Test
    void nonBearerAuthorizationHeader_ignoredAndChainContinues() throws Exception {
        // "Basic abc:def" — wrong scheme, must be ignored (not crash).
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        verify(jwt, never()).validateToken(anyString());
    }

    @Test
    void emptyBearerToken_doesNotValidate() throws Exception {
        // "Bearer " with nothing after — extracted as empty string,
        // StringUtils.hasText() catches it, no validation attempt.
        when(request.getHeader("Authorization")).thenReturn("Bearer ");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(jwt, never()).validateToken(anyString());
        verify(chain).doFilter(request, response);
    }

    // ── Valid built-in JWT ────────────────────────────────────────────────────

    @Test
    void validBuiltinJwt_authenticatesWithRoleFromClaim() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwt.validateToken("valid-token")).thenReturn(true);
        when(jwt.isBlacklisted("valid-token")).thenReturn(false);
        when(jwt.getUsername("valid-token")).thenReturn("alice");
        // Role MUST come from the claim, not be re-derived from username.
        // Pinned because the previous version of the filter granted both
        // ROLE_USER + ROLE_ADMIN to every authenticated user, which is now
        // the security regression to guard against.
        when(jwt.getRole("valid-token")).thenReturn("ROLE_ADMIN");

        filter.doFilter(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("alice");
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ADMIN");
        verify(chain).doFilter(request, response);
    }

    // ── Blacklisted (post-logout) JWT ─────────────────────────────────────────

    @Test
    void blacklistedJwt_noAuthEvenIfSignatureValid() throws Exception {
        // The load-bearing logout enforcement: a token blacklisted via
        // POST /auth/logout must be rejected EVEN IF its HMAC signature
        // would still pass. Without this branch, logout would be a no-op
        // until natural token expiry.
        when(request.getHeader("Authorization")).thenReturn("Bearer logged-out-token");
        when(jwt.validateToken("logged-out-token")).thenReturn(true);
        when(jwt.isBlacklisted("logged-out-token")).thenReturn(true);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        // Sanity: getUsername/getRole never called when blacklisted (short-circuit)
        verify(jwt, never()).getUsername(anyString());
        verify(jwt, never()).getRole(anyString());
    }

    // ── Invalid built-in JWT (no Keycloak fallback) ───────────────────────────

    @Test
    void invalidJwtNoKeycloakDecoder_noAuthAndChainContinues() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
        when(jwt.validateToken("bad-token")).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
        // No Keycloak decoder configured → invalid token = no auth, period.
    }

    // ── Span tagging on successful auth ───────────────────────────────────────

    @Test
    void successfulAuth_tagsCurrentSpanWithUsername() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwt.validateToken("valid-token")).thenReturn(true);
        when(jwt.isBlacklisted("valid-token")).thenReturn(false);
        when(jwt.getUsername("valid-token")).thenReturn("alice");
        when(jwt.getRole("valid-token")).thenReturn("ROLE_USER");

        Span currentSpan = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(currentSpan);
        when(currentSpan.tag(anyString(), anyString())).thenReturn(currentSpan);

        filter.doFilter(request, response, chain);

        // user.name tag is what makes Grafana Tempo show the username on
        // the request span — pinned because it's the load-bearing
        // observability link.
        verify(currentSpan).tag("user.name", "alice");
    }
}
