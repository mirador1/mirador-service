package com.example.springapi.auth;

import com.example.springapi.auth.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Spring Security filter chain for stateless JWT authentication.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li><b>STATELESS session policy</b> — no HttpSession is created or used. Each request
 *       must carry a valid JWT in the {@code Authorization: Bearer <token>} header.
 *       This is suitable for REST APIs consumed by frontends or other services.</li>
 *   <li><b>CSRF disabled</b> — CSRF protection is only relevant for session-based (cookie)
 *       authentication. With stateless JWT there is no session cookie to forge.</li>
 *   <li><b>Custom AuthenticationEntryPoint</b> — Spring Security defaults to returning HTTP 403
 *       for unauthenticated requests. We override this with 401 (Unauthorized), which is
 *       semantically correct: 403 means "authenticated but not authorized", while 401 means
 *       "no valid credentials provided".</li>
 * </ul>
 *
 * <h3>Endpoint access rules</h3>
 * <ul>
 *   <li>{@code POST /auth/login} — public (used to obtain a token)</li>
 *   <li>{@code /actuator/**} — public (Prometheus scraping, health probes)</li>
 *   <li>{@code /swagger-ui/**}, {@code /v3/api-docs/**} — public (API documentation)</li>
 *   <li>Everything else — requires a valid JWT</li>
 * </ul>
 *
 * <p>{@code @EnableWebSecurity} registers this configuration as the primary security
 * configuration and disables Spring Boot's default basic-auth auto-configuration.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    /**
     * Configures the security filter chain.
     *
     * <p>The {@link JwtAuthenticationFilter} is inserted before
     * {@link UsernamePasswordAuthenticationFilter} in the filter chain so that JWT
     * validation runs first and populates the {@code SecurityContext} before Spring Security's
     * built-in authentication filters are evaluated.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // No CSRF needed for stateless JWT APIs
                .csrf(AbstractHttpConfigurer::disable)
                // Do not create/use HttpSession — each request is independently authenticated
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // HttpMethod.POST is required here — passing a String would be treated as a URL pattern
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/v3/api-docs/**").permitAll()
                        .anyRequest().authenticated()
                )
                // Return 401 (not 403) for requests without valid credentials
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) ->
                                res.sendError(HttpServletResponse.SC_UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
