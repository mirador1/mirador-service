package com.mirador.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that assigns a unique request ID to every incoming HTTP request
 * and emits a structured access log entry after the response is committed.
 *
 * <h3>Request correlation</h3>
 * <p>If the caller supplies an {@code X-Request-Id} header (e.g., from an API gateway or
 * upstream service), its value is reused as-is (pass-through correlation). Otherwise a
 * random UUID is generated. The ID is:
 * <ul>
 *   <li>Put into the SLF4J {@link MDC} under the key {@code requestId} — any subsequent log
 *       statement within this thread will automatically include it, enabling log-line correlation
 *       in tools like Grafana Loki.</li>
 *   <li>Written back to the response as {@code X-Request-Id} so the caller can trace
 *       the request end-to-end, even if the ID was server-generated.</li>
 * </ul>
 *
 * <h3>Access logging</h3>
 * <p>After the full filter chain (including the controller) completes, a single structured
 * log line is emitted at INFO level: {@code http_access method=... uri=... status=... durationMs=...}.
 * This log is the raw material for Grafana Loki dashboards and log-based alerting.
 *
 * <h3>Priority</h3>
 * <p>{@code @Order(Ordered.HIGHEST_PRECEDENCE)} ensures this filter runs first in the chain
 * so that the request ID is available to all subsequent filters and handlers, including the
 * rate-limiting and idempotency filters.
 *
 * <p>{@code OncePerRequestFilter} guarantees the filter logic is executed exactly once per
 * request, even when the request is dispatched (e.g., via {@code RequestDispatcher.forward}).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    /** HTTP header name used to carry the request ID in both directions. */
    public static final String HEADER_NAME = "X-Request-Id";
    /** MDC key under which the request ID is stored for log enrichment. */
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Reuse the caller-supplied ID for end-to-end tracing, or generate a new one
        String requestId = request.getHeader(HEADER_NAME);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        // Enrich all log lines emitted during this request with the request ID
        MDC.put(MDC_KEY, requestId);
        // Echo the ID back so the caller can correlate their logs with ours
        response.setHeader(HEADER_NAME, requestId);

        long start = System.nanoTime();
        try {
            // Bind REQUEST_ID for the duration of the filter chain so any code in this request
            // scope can read RequestContext.REQUEST_ID.get() and receive the actual ID.  [Java 21+]
            ScopedValue.where(RequestContext.REQUEST_ID, requestId).call(() -> {
                filterChain.doFilter(request, response);
                return null;
            });
        } catch (ServletException | IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unexpected exception in scoped filter chain", e);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            // Structured access log — key=value format for easy Loki / LogQL parsing
            log.info("http_access method={} uri={} status={} durationMs={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);
            // Always remove the MDC key to prevent leakage into other requests on thread-pool reuse
            MDC.remove(MDC_KEY);
        }
    }
}