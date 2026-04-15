package com.mirador.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for OWASP security headers added on every HTTP response.
 * Uses Spring's MockHttpServletRequest/Response — no full context needed.
 */
class SecurityHeadersFilterTest {

    private SecurityHeadersFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new SecurityHeadersFilter();
        chain = mock(FilterChain.class);
    }

    @Test
    void allResponses_haveBaselineSecurityHeaders() throws Exception {
        var req = new MockHttpServletRequest("GET", "/customers");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(res.getHeader("X-XSS-Protection")).isEqualTo("0");
        assertThat(res.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        assertThat(res.getHeader("Permissions-Policy"))
                .isEqualTo("camera=(), microphone=(), geolocation=()");
    }

    @Test
    void regularPath_hasXFrameOptionsAndCSP() throws Exception {
        var req = new MockHttpServletRequest("GET", "/customers");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Content-Security-Policy"))
                .contains("default-src 'self'");
    }

    @Test
    void reportsPath_hasNoXFrameOptions_and_frameAncestorsCsp() throws Exception {
        var req = new MockHttpServletRequest("GET", "/reports/dependency-check");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        // X-Frame-Options must be absent so browsers allow iframe embedding from Angular.
        assertThat(res.getHeader("X-Frame-Options")).isNull();
        // frame-ancestors restricts embedding to same origin + Angular dev server only.
        assertThat(res.getHeader("Content-Security-Policy"))
                .contains("frame-ancestors")
                .contains("'self'");
    }

    @Test
    void swaggerPath_hasXFrameOptions_butNoCSP() throws Exception {
        var req = new MockHttpServletRequest("GET", "/swagger-ui/index.html");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Content-Security-Policy")).isNull();
    }

    @Test
    void apiDocsPath_hasXFrameOptions_butNoCSP() throws Exception {
        var req = new MockHttpServletRequest("GET", "/v3/api-docs");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(res.getHeader("Content-Security-Policy")).isNull();
    }
}
