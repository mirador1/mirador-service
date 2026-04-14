package com.example.customerservice.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
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
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "0");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        // CSP only on API endpoints — Swagger UI needs inline scripts and external resources
        String path = request.getRequestURI();
        if (!path.startsWith("/swagger-ui") && !path.startsWith("/v3/api-docs")) {
            response.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'");
        }

        filterChain.doFilter(request, response);
    }
}
