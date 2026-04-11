package com.example.springapi.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Spring Security filter that validates the JWT Bearer token on every incoming request.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Extract the {@code Bearer <token>} value from the {@code Authorization} header.</li>
 *   <li>If present and valid (signature + expiry verified by {@link JwtTokenProvider}),
 *       create a {@link UsernamePasswordAuthenticationToken} with a {@code ROLE_USER} authority
 *       and store it in {@link SecurityContextHolder} so that Spring Security sees the request
 *       as authenticated.</li>
 *   <li>Always call {@code chain.doFilter} — the filter never blocks requests itself;
 *       authorization (403/401) is handled downstream by Spring Security's access control rules.</li>
 * </ol>
 *
 * <h3>Why OncePerRequestFilter?</h3>
 * <p>Guarantees execution exactly once per request, regardless of internal forwards or includes
 * (e.g., error dispatcher). Without this, the filter could run twice for error pages.
 *
 * <h3>Credentials field</h3>
 * <p>The {@code credentials} field of the {@link UsernamePasswordAuthenticationToken} is {@code null}
 * because credentials (the raw password/token) should never be stored after authentication to
 * prevent accidental serialization or logging.
 *
 * <h3>Role assignment</h3>
 * <p>All authenticated users receive {@code ROLE_USER}. Finer-grained role assignment from
 * JWT claims (e.g., a {@code roles} claim) would be added in a Keycloak integration where
 * the token includes realm/client roles.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    /** Standard Bearer token prefix per RFC 6750. */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            String username = jwtTokenProvider.getUsername(token);
            // Credentials = null: never store the raw token after authentication
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    username,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Authenticated user '{}' via JWT", username);
        }
        // Always continue the chain — unauthenticated requests are handled by Spring Security rules
        filterChain.doFilter(request, response);
    }

    /**
     * Extracts the raw JWT from the {@code Authorization: Bearer <token>} header.
     * Returns {@code null} if the header is absent or malformed.
     */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
