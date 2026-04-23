package com.mirador.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link SecurityConfig} — focus on the two side-effect-free
 * beans (passwordEncoder + corsConfigurationSource). The SecurityFilterChain
 * bean requires a real HttpSecurity builder + Spring context, covered by
 * the integration tests in {@code AuthIntegrationTest}.
 *
 * <p>Pinned contracts:
 *   - PasswordEncoder is BCrypt with strength 10 (OWASP-recommended balance,
 *     ~100 ms per hash)
 *   - CORS allowed origins read from the {@code cors.allowed-origins}
 *     property (no hardcoded localhost — production uses different origins)
 *   - CORS allowed methods include all REST verbs + OPTIONS preflight
 *   - X-API-Version + Idempotency-Key in allowed headers (or every browser
 *     CORS preflight on /customers fails silently with status 0)
 *   - Security headers exposed for the Angular Security Demo
 */
// eslint-disable-next-line max-lines-per-function
class SecurityConfigTest {

    private SecurityConfig newConfig(List<String> origins) {
        SecurityConfig config = new SecurityConfig(
                mock(JwtAuthenticationFilter.class),
                mock(ApiKeyAuthenticationFilter.class)
        );
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        return config;
    }

    @Test
    void passwordEncoder_isBCryptStrength10() {
        // Pinned: strength 10 — OWASP balance. Lowering (≤8) makes
        // brute-forcing dictionary passwords feasible on a modern GPU;
        // raising (≥12) pushes hash time past 1s and creates UX pain
        // on every login/register call. The 10 is deliberate.
        SecurityConfig config = newConfig(List.of("http://localhost:4200"));

        PasswordEncoder encoder = config.passwordEncoder();

        assertThat(encoder).isInstanceOf(BCryptPasswordEncoder.class);
        // BCrypt's encoded form starts with $2a$10$ for strength 10.
        // A regression to a different strength would show as $2a$N$ with N≠10.
        String encoded = encoder.encode("test-password");
        assertThat(encoded).startsWith("$2a$10$");
    }

    @Test
    void passwordEncoder_isFunctional_canMatchPlainAgainstHash() {
        // Sanity: encode + matches works end-to-end. A custom encoder
        // mistakenly returning a NoOp would silently let plain-text
        // passwords through (every "matches" returns true).
        PasswordEncoder encoder = newConfig(List.of("http://localhost:4200")).passwordEncoder();
        String hash = encoder.encode("admin");

        assertThat(encoder.matches("admin", hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }

    @Test
    void corsConfigurationSource_appliesAllowedOriginsFromProperty() {
        // Pinned: CORS origins MUST come from the
        // cors.allowed-origins property — production prepends
        // https://prod-ui.example.com which dev doesn't know about.
        // A regression that hardcoded localhost would 403 every prod
        // browser request from the React/Angular app.
        var config = newConfig(List.of("https://app.example.com", "https://staging.example.com"));

        var corsConfig = corsForRequest(config, "/customers");

        assertThat(corsConfig.getAllowedOrigins())
                .containsExactly("https://app.example.com", "https://staging.example.com");
    }

    @Test
    void corsConfigurationSource_allowsAllRestVerbsPlusOptionsPreflight() {
        // Pinned: OPTIONS is the CORS preflight verb; missing it would
        // make the browser reject every cross-origin POST/PUT/DELETE
        // before it even reaches the server. The other 5 cover
        // every HTTP verb the API uses.
        var corsConfig = corsForRequest(newConfig(List.of("*")), "/customers");

        assertThat(corsConfig.getAllowedMethods())
                .containsExactlyInAnyOrder("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }

    @Test
    void corsConfigurationSource_includesXApiVersionInAllowedHeaders() {
        // Pinned: X-API-Version is the header-based version selector
        // (ADR-0020). Without it in the allowlist, the browser's CORS
        // preflight on GET /customers fails silently (status 0 in
        // fetch). Exactly the bug ADR-0033's Playwright E2E was built
        // to catch — and DID catch.
        var corsConfig = corsForRequest(newConfig(List.of("*")), "/customers");

        assertThat(corsConfig.getAllowedHeaders())
                .contains("X-API-Version");
    }

    @Test
    void corsConfigurationSource_includesIdempotencyKeyInAllowedHeaders() {
        // Pinned: Idempotency-Key is the convention for replayable POSTs
        // (return cached response on duplicate key). UI uses it on
        // POST /customers to prevent double-submit. Missing from the
        // allowlist = preflight failure on every idempotent POST.
        var corsConfig = corsForRequest(newConfig(List.of("*")), "/customers");

        assertThat(corsConfig.getAllowedHeaders())
                .contains("Idempotency-Key");
    }

    @Test
    void corsConfigurationSource_doesNotUseWildcardForAllowedHeaders() {
        // Pinned: explicit allowlist (defense-in-depth) — wildcard "*"
        // is a security anti-pattern that would also bypass any future
        // header-based filtering rules. The list IS hand-curated and
        // must STAY hand-curated.
        var corsConfig = corsForRequest(newConfig(List.of("*")), "/customers");

        assertThat(corsConfig.getAllowedHeaders()).doesNotContain("*");
    }

    @Test
    void corsConfigurationSource_exposesSecurityHeadersForAngularSecurityDemo() {
        // Pinned: the /security demo page in the UI reads X-Frame-Options,
        // CSP, etc. via HttpClient observe:'response'. Without explicit
        // exposure (Access-Control-Expose-Headers), the browser hides
        // these headers from JS even though they were on the wire — the
        // Security demo page would show "no headers" everywhere.
        var corsConfig = corsForRequest(newConfig(List.of("*")), "/customers");

        assertThat(corsConfig.getExposedHeaders())
                .contains("X-Content-Type-Options", "X-Frame-Options",
                        "Content-Security-Policy", "Strict-Transport-Security");
    }

    @Test
    void corsConfigurationSource_allowsCredentialsForAuthCookies() {
        // Pinned: allowCredentials=true is required for the browser
        // to send cookies / Authorization headers on cross-origin requests.
        // Switching to false would block the Authorization: Bearer
        // header from reaching the backend on every cross-origin call.
        var corsConfig = corsForRequest(newConfig(List.of("https://app.example.com")), "/customers");

        assertThat(corsConfig.getAllowCredentials()).isTrue();
    }

    /**
     * Helper: read the CORS config registered for a path via the source's
     * internal map (avoids the HttpServletRequest mock that requires
     * HttpServletMapping setup since Spring 6.1).
     */
    private static CorsConfiguration corsForRequest(SecurityConfig config, String path) {
        var source = (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();
        // The source registers configs under URL patterns ("/**" in our case).
        return source.getCorsConfigurations().get("/**");
    }
}
