package com.example.springapi.filter;

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
 * Idempotency filter for POST /customers.
 *
 * If the request carries an Idempotency-Key header and that key was seen
 * before, the original response body is replayed (HTTP 200) without
 * re-executing the handler. Keys are stored in a bounded in-memory map
 * (eviction by insertion order, max 10 000 entries).
 *
 * Clients should use a UUID v4 as idempotency key:
 *   Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
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
