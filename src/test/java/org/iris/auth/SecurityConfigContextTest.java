package org.iris.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Plain-Spring (non-Boot) context test that loads {@link SecurityConfig}
 * plus the two filter mocks it depends on. The point is purely
 * structural: Spring builds the {@link SecurityFilterChain} bean,
 * which evaluates every lambda in {@code securityFilterChain(HttpSecurity)}
 * — JaCoCo coverage Spring Security's filter-chain DSL needs (the
 * lambdas only execute at bean-build time, not per-request).
 *
 * <p>Plain {@code @SpringJUnitConfig} avoids Spring Boot's auto-config
 * (OpenTelemetry, Hibernate, Kafka, etc.) which previously polluted
 * other tests' static state when @SpringBootTest was used here. Only
 * what's explicitly imported via {@link Config} loads.
 */
@SpringJUnitConfig(SecurityConfigContextTest.Config.class)
@WebAppConfiguration
@TestPropertySource(properties = "cors.allowed-origins=http://localhost:4200")
class SecurityConfigContextTest {

    @Autowired SecurityFilterChain securityFilterChain;

    @Test
    void securityFilterChainBean_isBuilt() {
        // Pinned : the FilterChain bean must be created. Spring evaluates
        // every lambda inside SecurityConfig#securityFilterChain at
        // bean-build time — covers the requestMatchers / sessionManagement
        // / exceptionHandling DSL bodies that unit tests can't reach.
        assertThat(securityFilterChain).isNotNull();
        // The chain MUST register a non-trivial filter list (Spring
        // Security itself contributes ~10 filters, plus our two custom
        // filters added via .addFilterBefore).
        assertThat(securityFilterChain.getFilters()).isNotEmpty();
    }

    /** Minimal config: SecurityConfig + the two filter mocks it depends on. */
    @Configuration
    @Import(SecurityConfig.class)
    static class Config {
        @Bean JwtAuthenticationFilter jwtAuthenticationFilter() {
            return mock(JwtAuthenticationFilter.class);
        }
        @Bean ApiKeyAuthenticationFilter apiKeyAuthenticationFilter() {
            return mock(ApiKeyAuthenticationFilter.class);
        }
    }
}
