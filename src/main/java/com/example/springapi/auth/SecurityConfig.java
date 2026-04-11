package com.example.springapi.auth;

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

import jakarta.servlet.http.HttpServletResponse;

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

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/customers").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

}
