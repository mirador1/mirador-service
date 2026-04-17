package com.mirador.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds OWASP-recommended security headers to every HTTP response.
 *
 * <p>Spring Security sets some of these by default, but not all — this filter
 * ensures a complete, consistent set regardless of the Security configuration.
 *
 * <ul>
 *   <li>{@code X-Content-Type-Options: nosniff} — prevents MIME-type sniffing attacks
 *       (browser won't reinterpret a CSV as HTML).</li>
 *   <li>{@code X-Frame-Options: DENY} — blocks clickjacking by preventing the page
 *       from being embedded in an iframe.</li>
 *   <li>{@code X-XSS-Protection: 0} — disables the broken legacy XSS filter in old
 *       browsers (OWASP recommends off + CSP instead).</li>
 *   <li>{@code Referrer-Policy: strict-origin-when-cross-origin} — limits URL leakage
 *       in the Referer header for cross-origin requests.</li>
 *   <li>{@code Content-Security-Policy: default-src 'self'} — baseline CSP that blocks
 *       inline scripts and external resource loading. Swagger UI endpoints are excluded
 *       because they require inline scripts and CDN resources.</li>
 *   <li>{@code Permissions-Policy} — disables access to sensitive browser APIs
 *       (camera, microphone, geolocation) that a REST API should never need.</li>
 * </ul>
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-XSS-Protection", "0");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        String path = request.getRequestURI();

        // /reports/** is intentionally embedded as an iframe in the Angular frontend (quality page).
        // X-Frame-Options must be absent (not DENY) so browsers allow cross-origin framing from
        // http://localhost:4200 in dev and from the same origin in prod.
        // frame-ancestors is set to 'self' so only same-origin pages can embed them — tighter
        // than a wildcard but still allows the Angular app (same host, different port in dev).
        // Note: the Maven site is served by a dedicated nginx container (port 8083), not by this app.
        boolean isEmbeddable = path.startsWith("/reports/");

        if (!isEmbeddable) {
            response.setHeader("X-Frame-Options", "DENY");
        }

        // CSP: skip Swagger UI (needs inline scripts) and embeddable report paths.
        // For embeddable paths, send a narrow frame-ancestors CSP instead of the full policy —
        // the Maven site HTML has its own inline styles that a 'self' default-src would block.
        if (isEmbeddable) {
            // Allow embedding from same origin and localhost:4200 (Angular dev server).
            // In production both are on the same origin so 'self' alone is sufficient.
            response.setHeader("Content-Security-Policy",
                    "frame-ancestors 'self' http://localhost:4200");
        } else if (!path.startsWith("/swagger-ui") && !path.startsWith("/v3/api-docs")) {
            response.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
        }

        filterChain.doFilter(request, response);
    }
}
