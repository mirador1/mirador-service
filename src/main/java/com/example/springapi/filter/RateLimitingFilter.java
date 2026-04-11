package com.example.springapi.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-IP token-bucket rate limiter backed by Bucket4j.
 *
 * Each remote IP gets its own bucket refilled at a configurable rate.
 * Exceeded requests receive HTTP 429 with standard Retry-After and
 * X-Rate-Limit-* headers so clients can back off gracefully.
 *
 * Defaults (overridable via application.yml):
 *   app.rate-limit.capacity     = 100  tokens per window
 *   app.rate-limit.refill-tokens = 100  tokens added at each refill
 *   app.rate-limit.refill-seconds = 60  window duration in seconds
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingFilter.class);

    private static final String HEADER_LIMIT_REMAINING = "X-Rate-Limit-Remaining";
    private static final String HEADER_RETRY_AFTER     = "Retry-After";

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private final long capacity;
    private final long refillTokens;
    private final long refillSeconds;

    public RateLimitingFilter(
            @Value("${app.rate-limit.capacity:100}") long capacity,
            @Value("${app.rate-limit.refill-tokens:100}") long refillTokens,
            @Value("${app.rate-limit.refill-seconds:60}") long refillSeconds) {
        this.capacity      = capacity;
        this.refillTokens  = refillTokens;
        this.refillSeconds = refillSeconds;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String ip = resolveClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(ip, this::newBucket);

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader(HEADER_LIMIT_REMAINING, String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long retryAfterSeconds = (probe.getNanosToWaitForRefill() / 1_000_000_000) + 1;
            log.warn("rate_limit_exceeded ip={} retry_after={}s", ip, retryAfterSeconds);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));
            response.getWriter().write(
                    """
                    {"type":"about:blank","title":"Too Many Requests","status":429,\
                    "detail":"Rate limit exceeded. Please retry after %d seconds."}
                    """.formatted(retryAfterSeconds).strip()
            );
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Bucket newBucket(String ip) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(refillTokens, Duration.ofSeconds(refillSeconds))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Resolves the real client IP, honoring the X-Forwarded-For header when
     * the request arrives through a reverse proxy (nginx, AWS ALB, …).
     */
    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // X-Forwarded-For: client, proxy1, proxy2 — take the first entry
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
