package com.mirador.ml;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Spring configuration for the ML slice.
 *
 * <p>Defines the {@link Clock} bean that {@link ChurnFeatureExtractor}
 * consumes for "current time" — kept as a Spring bean rather than a
 * static call so unit tests can inject a {@link Clock#fixed} clock and
 * the cross-language smoke test (Phase G of shared ADR-0061) can pin
 * Java + Python to the same reference instant.
 *
 * <p>No other ML-specific beans yet ; {@link ChurnPredictor} is a
 * {@link org.springframework.stereotype.Service} and is picked up by
 * component scanning.
 */
@Configuration
public class MlConfig {

    /**
     * Default UTC clock — used everywhere Clock is injected unless a
     * test substitutes a fixed clock via {@code @MockBean}.
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
