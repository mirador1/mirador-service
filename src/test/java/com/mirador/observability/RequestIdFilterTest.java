package com.mirador.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for request-ID propagation and MDC enrichment.
 */
class RequestIdFilterTest {

    private RequestIdFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RequestIdFilter();
        chain = mock(FilterChain.class);
    }

    @Test
    void existingRequestId_isReused() throws Exception {
        var req = new MockHttpServletRequest("GET", "/customers");
        req.addHeader(RequestIdFilter.HEADER_NAME, "my-trace-id-123");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        assertThat(res.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("my-trace-id-123");
    }

    @Test
    void missingRequestId_generatesUUID() throws Exception {
        var req = new MockHttpServletRequest("GET", "/customers");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        String id = res.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(id).isNotBlank();
        // UUID v4 pattern
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void blankRequestId_generatesNewUUID() throws Exception {
        var req = new MockHttpServletRequest("POST", "/customers");
        req.addHeader(RequestIdFilter.HEADER_NAME, "   ");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        String id = res.getHeader(RequestIdFilter.HEADER_NAME);
        assertThat(id).isNotBlank().isNotEqualTo("   ");
    }

    @Test
    void twoParallelRequests_receiveDifferentIds() throws Exception {
        var req1 = new MockHttpServletRequest("GET", "/customers");
        var req2 = new MockHttpServletRequest("GET", "/customers");
        var res1 = new MockHttpServletResponse();
        var res2 = new MockHttpServletResponse();

        filter.doFilter(req1, res1, chain);
        filter.doFilter(req2, res2, chain);

        assertThat(res1.getHeader(RequestIdFilter.HEADER_NAME))
                .isNotEqualTo(res2.getHeader(RequestIdFilter.HEADER_NAME));
    }
}
