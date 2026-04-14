package com.mirador.resilience;

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
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * <p>The cache is a bounded {@link LinkedHashMap} (insertion-order) capped at 10 000 entries.
 * When the cap is reached, the oldest-inserted entry is evicted automatically via
 * {@code removeEldestEntry} — no bulk clear, so most cached responses survive. The map is
 * wrapped with {@link Collections#synchronizedMap} for thread-safety.
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

    /** Cached idempotent response: status code + body. */
    private record CachedResponse(int status, String body) {}

    // Insertion-order bounded map: removes the oldest entry when MAX_ENTRIES is exceeded.
    // Wrapped with synchronizedMap because LinkedHashMap is not thread-safe.
    @SuppressWarnings("serial")
    private final Map<String, CachedResponse> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(MAX_ENTRIES, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

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

        CachedResponse cached = cache.get(key);
        if (cached != null) {
            // Replay the original status code (e.g. 201 Created for POST /customers),
            // not a hardcoded 200 — idempotency means "same response", not "always OK".
            log.info("idempotency_hit key={} status={}", key, cached.status());
            response.setStatus(cached.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(cached.body());
            return;
        }

        // Wrap to capture response body
        var wrapper = new ContentCachingResponseWrapper(response);
        chain.doFilter(request, wrapper);

        // getCharacterEncoding() may return null if Content-Type has no charset — default to UTF-8.
        String charset = wrapper.getCharacterEncoding() != null
                ? wrapper.getCharacterEncoding()
                : StandardCharsets.UTF_8.name();
        String body = new String(wrapper.getContentAsByteArray(), charset);
        // Cache any 2xx response — not just 200. POST /customers returns 201 Created,
        // so caching only 200 would make the idempotency key silently ineffective for the
        // main use case. Non-2xx (4xx/5xx) are deliberately NOT cached: the client should
        // be able to retry after fixing the request or after a transient server error.
        if (wrapper.getStatus() >= 200 && wrapper.getStatus() < 300 && !body.isBlank()) {
            cache.put(key, new CachedResponse(wrapper.getStatus(), body));
            log.info("idempotency_store key={} status={}", key, wrapper.getStatus());
        }
        wrapper.copyBodyToResponse();
    }
}
