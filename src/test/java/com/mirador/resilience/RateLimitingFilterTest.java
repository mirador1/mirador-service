package com.mirador.resilience;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for per-IP token-bucket rate limiting.
 * Uses real Bucket4j buckets — no mocking of the token logic.
 */
class RateLimitingFilterTest {

    /** Filter with a tiny bucket (3 tokens) to trigger 429 quickly in tests. */
    private final RateLimitingFilter filter = new RateLimitingFilter(3, 3, 60);

    @Test
    void withinLimit_chainInvoked_remainingHeaderSet() throws Exception {
        var req = new MockHttpServletRequest("GET", "/customers");
        req.setRemoteAddr("10.0.0.1");
        var res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(req, res);
        assertThat(res.getHeader("X-Rate-Limit-Remaining")).isNotNull();
    }

    @Test
    void exceedingLimit_returns429_chainNotInvoked() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        String ip = "10.0.0.2";

        // Exhaust the 3-token bucket
        for (int i = 0; i < 3; i++) {
            var req = new MockHttpServletRequest("GET", "/customers");
            req.setRemoteAddr(ip);
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }

        // 4th request must be rate-limited
        var req = new MockHttpServletRequest("GET", "/customers");
        req.setRemoteAddr(ip);
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(res.getHeader("Retry-After")).isNotNull();
        assertThat(res.getContentAsString()).contains("Rate limit exceeded");
        verify(chain, times(3)).doFilter(any(), any()); // only the first 3 reached chain
    }

    @Test
    void xForwardedFor_usesFirstIp() throws Exception {
        var req1 = new MockHttpServletRequest("GET", "/customers");
        req1.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.3");
        var req2 = new MockHttpServletRequest("GET", "/customers");
        req2.addHeader("X-Forwarded-For", "203.0.113.1, 10.0.0.4"); // same client, different proxy

        var res1 = new MockHttpServletResponse();
        var res2 = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req1, res1, chain);
        filter.doFilter(req2, res2, chain);

        // Both requests share the same per-IP bucket (203.0.113.1)
        long remaining1 = Long.parseLong(res1.getHeader("X-Rate-Limit-Remaining"));
        long remaining2 = Long.parseLong(res2.getHeader("X-Rate-Limit-Remaining"));
        assertThat(remaining2).isLessThan(remaining1);
    }

    @Test
    void differentIps_haveIndependentBuckets() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // Exhaust IP A
        for (int i = 0; i < 3; i++) {
            var req = new MockHttpServletRequest("GET", "/customers");
            req.setRemoteAddr("10.0.0.10");
            filter.doFilter(req, new MockHttpServletResponse(), chain);
        }

        // IP B should still have a full bucket
        var reqB = new MockHttpServletRequest("GET", "/customers");
        reqB.setRemoteAddr("10.0.0.11");
        var resB = new MockHttpServletResponse();
        filter.doFilter(reqB, resB, chain);

        assertThat(resB.getStatus()).isNotEqualTo(429);
    }

    // Needed because chain is a raw mock in the exhaust loops
    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
