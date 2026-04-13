package com.example.customerservice.auth;

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
 * <h3>Role-based access</h3>
 * <ul>
 *   <li>{@code GET /customers} — requires {@code ROLE_USER} or {@code ROLE_ADMIN}</li>
 *   <li>{@code POST /customers} — requires {@code ROLE_ADMIN}</li>
 * </ul>
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
                        // Actuator: only health probes, info, and prometheus are public.
                        // /actuator/prometheus is accessed by Prometheus — restrict to internal
                        // network via network policy, not Spring Security (Prometheus sends no JWT).
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")             // other actuator endpoints require auth
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll() // Swagger UI
                        .requestMatchers("/v3/api-docs/**").permitAll()               // OpenAPI spec
                        .requestMatchers("/ws/**").permitAll()                        // WebSocket STOMP endpoint
                        .requestMatchers(HttpMethod.POST, "/customers").hasRole("ADMIN") // write access — ROLE_ADMIN only
                        .requestMatchers(HttpMethod.POST, "/customers/batch").hasRole("ADMIN") // batch import — ROLE_ADMIN only
                        .requestMatchers(HttpMethod.PUT, "/customers/**").hasRole("ADMIN")     // update — ROLE_ADMIN only
                        .requestMatchers(HttpMethod.DELETE, "/customers/**").hasRole("ADMIN")   // delete — ROLE_ADMIN only
                        .anyRequest().authenticated()                                 // all other endpoints require a valid JWT
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

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
