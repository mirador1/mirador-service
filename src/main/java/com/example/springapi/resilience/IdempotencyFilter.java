package com.example.springapi.resilience;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servlet filter that makes {@code POST /customers} idempotent.
 *
 * <h3>Problem</h3>
 * <p>HTTP POST is not idempotent by default. If a client retries a request after a network
 * timeout (without knowing whether the server received the first attempt), a customer may be
 * created twice. This filter prevents that by caching responses keyed on the caller-supplied
 * {@code Idempotency-Key} header.
 *
 * <h3>Mechanism</h3>
 * <ol>
 *   <li>On the first request with a given key: the filter lets the request proceed normally
 *       via {@code chain.doFilter}, wraps the response with a {@link ContentCachingResponseWrapper}
 *       to capture the body, then stores the body in the in-memory cache.</li>
 *   <li>On subsequent requests with the same key: the cached body is written directly to the
 *       response (HTTP 200) without invoking the controller — the customer is NOT created again.</li>
 *   <li>If no {@code Idempotency-Key} header is present, the filter is bypassed entirely.</li>
 * </ol>
 *
 * <h3>Scope</h3>
 * <p>Only applies to {@code POST} requests to paths starting with {@code /customers}.
 * All other requests are skipped via {@link #shouldNotFilter}.
 *
 * <h3>Cache eviction</h3>
 * <p>The cache is a bounded {@link ConcurrentHashMap} capped at 10 000 entries. When full,
 * the entire map is cleared (a blunt but simple eviction). For production use, replace with
 * a Caffeine cache with TTL-based expiry to avoid replaying very old responses.
 *
 * <h3>Client usage</h3>
 * <pre>
 *   Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
 * </pre>
 * Clients should use a UUID v4. The same key can safely be retried any number of times.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    static final String HEADER = "Idempotency-Key";
    private static final int MAX_ENTRIES = 10_000;

    // Simple bounded map: evict oldest when full
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>(MAX_ENTRIES);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/customers");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (key == null || key.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        String cached = cache.get(key);
        if (cached != null) {
            log.info("idempotency_hit key={}", key);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(cached);
            return;
        }

        // Wrap to capture response body
        var wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);

        String body = new String(wrapper.getContentAsByteArray(), wrapper.getCharacterEncoding());
        if (wrapper.getStatus() == HttpServletResponse.SC_OK && !body.isBlank()) {
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear(); // simple eviction — replace with Caffeine for production
            }
            cache.put(key, body);
            log.info("idempotency_store key={}", key);
        }
        wrapper.copyBodyToResponse();
    }
}
