package com.example.customerservice.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Alternative authentication via {@code X-API-Key} header for machine-to-machine calls.
 *
 * <p>Allows automated systems (CI pipelines, monitoring scripts, cron jobs) to call the
 * API without first obtaining a JWT via {@code POST /auth/login}. The API key is a
 * static secret configured via {@code app.api-key} in {@code application.yml}.
 *
 * <p>This filter runs <em>before</em> {@link JwtAuthenticationFilter} in the filter chain.
 * If the API key matches, the SecurityContext is populated immediately and the JWT filter
 * skips authentication (it checks {@code SecurityContextHolder} before proceeding).
 *
 * <p>In production, use a secrets manager (Vault, AWS SSM) to inject the key —
 * never hardcode it in application.yml.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String API_KEY_HEADER = "X-API-Key";

    private final String apiKey;

    public ApiKeyAuthenticationFilter(@Value("${app.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        if (StringUtils.hasText(apiKey)) {
            String provided = request.getHeader(API_KEY_HEADER);
            if (apiKey.equals(provided)) {
                var auth = new UsernamePasswordAuthenticationToken(
                        "api-key-user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"),
                                new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(auth);
                log.debug("Authenticated via X-API-Key header");
            }
        }

        filterChain.doFilter(request, response);
    }
}
