package com.mirador.resilience;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for POST /customers idempotency via Idempotency-Key header.
 */
class IdempotencyFilterTest {

    private IdempotencyFilter filter;

    @BeforeEach
    void setUp() {
        filter = new IdempotencyFilter();
    }

    // --- shouldNotFilter ---

    @Test
    void getRequest_isSkipped() throws Exception {
        var req = new MockHttpServletRequest("GET", "/customers");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void postToOtherPath_isSkipped() throws Exception {
        var req = new MockHttpServletRequest("POST", "/orders");
        assertThat(filter.shouldNotFilter(req)).isTrue();
    }

    @Test
    void postToCustomers_isNotSkipped() throws Exception {
        var req = new MockHttpServletRequest("POST", "/customers");
        assertThat(filter.shouldNotFilter(req)).isFalse();
    }

    // --- doFilterInternal ---

    @Test
    void noIdempotencyKey_chainInvokedDirectly() throws Exception {
        var req = new MockHttpServletRequest("POST", "/customers");
        var res = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void firstRequest_chainExecuted_responseStored() throws Exception {
        var req = new MockHttpServletRequest("POST", "/customers");
        req.addHeader(IdempotencyFilter.HEADER, "key-abc");
        var res = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        // Simulate controller writing a JSON body with status 200
        doAnswer(invocation -> {
            var wrappedRes = (jakarta.servlet.http.HttpServletResponse) invocation.getArgument(1);
            wrappedRes.setStatus(200);
            wrappedRes.getWriter().write("{\"id\":1}");
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(req, res, chain);

        verify(chain, times(1)).doFilter(any(), any());
    }

    @Test
    void secondRequest_sameKey_chainNotInvoked_cachedBodyReturned() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // First request — populate cache
        var req1 = new MockHttpServletRequest("POST", "/customers");
        req1.addHeader(IdempotencyFilter.HEADER, "key-xyz");
        var res1 = new MockHttpServletResponse();
        doAnswer(invocation -> {
            var wrappedRes = (jakarta.servlet.http.HttpServletResponse) invocation.getArgument(1);
            wrappedRes.setStatus(200);
            wrappedRes.getWriter().write("{\"id\":42}");
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilter(req1, res1, chain);

        // Second request — same key
        var req2 = new MockHttpServletRequest("POST", "/customers");
        req2.addHeader(IdempotencyFilter.HEADER, "key-xyz");
        var res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, chain);

        // Chain must not be called a second time
        verify(chain, times(1)).doFilter(any(), any());
        assertThat(res2.getStatus()).isEqualTo(200);
        assertThat(res2.getContentAsString()).isEqualTo("{\"id\":42}");
    }

    @Test
    void createdResponse_201_cachedAndReplayed() throws Exception {
        // POST /customers returns 201 Created — the filter must cache 201, not just 200,
        // otherwise idempotency would be silently ineffective for the primary use case.
        FilterChain chain = mock(FilterChain.class);

        var req1 = new MockHttpServletRequest("POST", "/customers");
        req1.addHeader(IdempotencyFilter.HEADER, "key-201");
        var res1 = new MockHttpServletResponse();
        doAnswer(invocation -> {
            var wrappedRes = (jakarta.servlet.http.HttpServletResponse) invocation.getArgument(1);
            wrappedRes.setStatus(201);
            wrappedRes.getWriter().write("{\"id\":99}");
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilter(req1, res1, chain);

        // Replay — chain must not be called, 201 must be preserved
        var req2 = new MockHttpServletRequest("POST", "/customers");
        req2.addHeader(IdempotencyFilter.HEADER, "key-201");
        var res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, chain);

        verify(chain, times(1)).doFilter(any(), any());
        assertThat(res2.getStatus()).isEqualTo(201);
        assertThat(res2.getContentAsString()).isEqualTo("{\"id\":99}");
    }

    @Test
    void nonOkResponse_notCached_chainCalledOnRetry() throws Exception {
        FilterChain chain = mock(FilterChain.class);

        // First request — 400 response, must not be cached
        var req1 = new MockHttpServletRequest("POST", "/customers");
        req1.addHeader(IdempotencyFilter.HEADER, "key-fail");
        var res1 = new MockHttpServletResponse();
        doAnswer(invocation -> {
            var wrappedRes = (jakarta.servlet.http.HttpServletResponse) invocation.getArgument(1);
            wrappedRes.setStatus(400);
            wrappedRes.getWriter().write("{\"error\":\"bad\"}");
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilter(req1, res1, chain);

        // Second request — same key, chain should be invoked again
        var req2 = new MockHttpServletRequest("POST", "/customers");
        req2.addHeader(IdempotencyFilter.HEADER, "key-fail");
        var res2 = new MockHttpServletResponse();
        filter.doFilter(req2, res2, chain);

        verify(chain, times(2)).doFilter(any(), any());
    }
}
