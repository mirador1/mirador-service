package com.mirador.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApiKeyAuthenticationFilter}.
 *
 * <p>Covers the four behavioural cases:
 * <ol>
 *   <li>Configured key + matching header → SecurityContext populated with USER + ADMIN.</li>
 *   <li>Configured key + wrong header → context untouched.</li>
 *   <li>Configured key + no header → context untouched.</li>
 *   <li>Empty key (default for tests / dev) → filter is a no-op even if a header arrives,
 *       guarding against authentication-via-empty-string. Doubly important because
 *       {@code @Value("${app.api-key:}")} silently defaults to "" when the property is
 *       missing — without the {@code StringUtils.hasText} guard, anyone could
 *       authenticate by sending {@code X-API-Key: ""}.</li>
 * </ol>
 *
 * <p>Filter chain `doFilter` MUST always be called (even on auth failure) so the request
 * continues through the chain — verified in every case.
 */
class ApiKeyAuthenticationFilterTest {

    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchingApiKey_authenticatesAsAdminUser() throws Exception {
        var filter = new ApiKeyAuthenticationFilter("secret-key-123");
        when(request.getHeader("X-API-Key")).thenReturn("secret-key-123");

        filter.doFilter(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo("api-key-user");
        assertThat(auth.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
        verify(chain).doFilter(request, response);
    }

    @Test
    void wrongApiKey_doesNotAuthenticate() throws Exception {
        var filter = new ApiKeyAuthenticationFilter("secret-key-123");
        when(request.getHeader("X-API-Key")).thenReturn("wrong-key");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingHeader_doesNotAuthenticate() throws Exception {
        var filter = new ApiKeyAuthenticationFilter("secret-key-123");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void emptyConfiguredKey_skipsAuthEvenIfHeaderProvided() throws Exception {
        // Critical guard: `@Value("${app.api-key:}")` defaults to "" when the
        // property isn't set. Without the StringUtils.hasText() check, any
        // caller sending `X-API-Key: ""` (or no header at all + matching empty)
        // could authenticate. Test pins the guard so a future refactor that
        // removes hasText() fails fast.
        var filter = new ApiKeyAuthenticationFilter("");
        when(request.getHeader("X-API-Key")).thenReturn("");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void emptyConfiguredKey_neverReadsHeader() throws Exception {
        // When the key isn't configured, the filter shouldn't even bother
        // reading the header — small perf optimisation that's worth pinning
        // because it doubles as the security guard.
        var filter = new ApiKeyAuthenticationFilter("");

        filter.doFilter(request, response, chain);

        verify(request, never()).getHeader("X-API-Key");
        verify(chain).doFilter(request, response);
    }
}
