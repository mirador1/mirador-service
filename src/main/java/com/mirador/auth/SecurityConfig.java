package com.mirador.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.List;

/**
 * Spring Security filter chain supporting two authentication modes:
 *
 * <h3>Mode 1 — Simple JWT (always active)</h3>
 * <p>The application's own {@link JwtAuthenticationFilter} validates tokens issued by
 * {@link JwtTokenProvider} ({@code POST /auth/login}).
 *
 * <h3>Mode 2 — Keycloak OAuth2 (activated by {@code KEYCLOAK_URL})</h3>
 * <p>When {@code KEYCLOAK_URL} is set, the same {@link JwtAuthenticationFilter} also
 * validates Keycloak-issued JWTs via the {@link JwtDecoder} bean. Roles are extracted
 * from the {@code realm_access.roles} claim.
 *
 * <p>A single filter handles both modes to avoid the interference caused by Spring
 * Security's {@code BearerTokenAuthenticationFilter}: when two separate authentication
 * filters run (custom + resource server), the resource server filter clears a valid
 * SecurityContext set by the custom filter on built-in tokens.
 *
 * <h3>Three-tier role model</h3>
 * <table border="1">
 *   <tr><th>Role</th><th>Credentials</th><th>Permissions</th></tr>
 *   <tr><td>{@code ROLE_ADMIN}</td><td>admin / admin</td><td>Full access: read, write, delete, admin endpoints</td></tr>
 *   <tr><td>{@code ROLE_USER}</td><td>user / user</td><td>Read + write: GET, POST, PUT — cannot delete</td></tr>
 *   <tr><td>{@code ROLE_READER}</td><td>viewer / viewer</td><td>Read-only: GET endpoints only</td></tr>
 * </table>
 *
 * <h3>Method-level security</h3>
 * <p>{@code @EnableMethodSecurity} activates {@code @PreAuthorize} on service/controller methods.
 *
 * [Spring Security 6+ / Spring Boot 4]
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiKeyAuthenticationFilter apiKeyAuthenticationFilter;

    @Value("${cors.allowed-origins:http://localhost:4200}")
    private List<String> allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          ApiKeyAuthenticationFilter apiKeyAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiKeyAuthenticationFilter = apiKeyAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CSRF protection is irrelevant for stateless REST APIs authenticated via Bearer tokens:
                // there is no session cookie that a CSRF attack could hijack.
                .csrf(AbstractHttpConfigurer::disable)
                // Never create an HttpSession — every request must carry its own JWT.
                // STATELESS also prevents Spring Security from storing the SecurityContext between requests.
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()  // public token endpoint
                        .requestMatchers(HttpMethod.POST, "/auth/refresh").permitAll() // token refresh (validates existing JWT internally)
                        .requestMatchers("/demo/security/**").permitAll()             // security demo endpoints (educational)
                        .requestMatchers(HttpMethod.GET, "/customers/stream").permitAll() // SSE stream — EventSource cannot send headers
                        // Actuator: root discovery + safe read-only probes are public.
                        // /actuator (root) lists available endpoints — no sensitive data.
                        // /actuator/prometheus is scraped by Prometheus (no JWT available).
                        // Sensitive endpoints (env, beans, heapdump, etc.) remain ADMIN-only.
                        .requestMatchers("/actuator", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus", "/actuator/metrics/**",
                                "/actuator/loggers", "/actuator/loggers/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")             // heapdump, env, beans, etc. require ADMIN
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll() // Swagger UI
                        .requestMatchers("/v3/api-docs/**").permitAll()               // OpenAPI spec
                        .requestMatchers("/ws/**").permitAll()                        // WebSocket STOMP endpoint
                        // Three-tier role model:
                        //   ROLE_ADMIN  — full access (read, write, delete, admin endpoints)
                        //   ROLE_USER   — read + write; cannot delete (POST/PUT allowed)
                        //   ROLE_READER — read-only; falls through to anyRequest().authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/customers/**").hasRole("ADMIN")           // delete — ROLE_ADMIN only
                        .requestMatchers(HttpMethod.POST, "/customers").hasAnyRole("ADMIN", "USER")     // create — ROLE_ADMIN or ROLE_USER
                        .requestMatchers(HttpMethod.POST, "/customers/batch").hasAnyRole("ADMIN", "USER") // batch — ROLE_ADMIN or ROLE_USER
                        .requestMatchers(HttpMethod.PUT, "/customers/**").hasAnyRole("ADMIN", "USER")    // update — ROLE_ADMIN or ROLE_USER
                        .requestMatchers(HttpMethod.PATCH, "/customers/**").hasAnyRole("ADMIN", "USER")  // partial update — ROLE_ADMIN or ROLE_USER
                        .anyRequest().authenticated()                                 // GET and all other endpoints: any authenticated user (incl. ROLE_READER)
                )
                // Return 401 (not a redirect to a login page) for missing or invalid tokens.
                // Spring Security's default entry point performs a redirect to /login — wrong for REST APIs.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                // Run JwtAuthenticationFilter before Spring Security's default UsernamePasswordAuthenticationFilter
                // so the SecurityContext is populated before authorization checks run.
                .addFilterBefore(apiKeyAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * BCrypt password encoder, strength 10.
     * OWASP-recommended balance between security (~100ms per hash) and user experience.
     * Injected into {@link DataInitializer} and {@link AuthController} for credential validation.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 10 is the OWASP-recommended balance between security and performance (~100ms per hash)
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        // Expose security headers so the Angular Security Demo can read them via HttpClient observe:'response'
        config.setExposedHeaders(List.of(
                "X-Content-Type-Options", "X-Frame-Options", "X-XSS-Protection",
                "Referrer-Policy", "Content-Security-Policy", "Permissions-Policy",
                "Strict-Transport-Security"
        ));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
