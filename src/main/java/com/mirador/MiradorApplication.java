package com.mirador;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Spring Boot 4 observable service.
 *
 * <p>This application demonstrates production-grade operational patterns on top of a simple
 * customer management API:
 * <ul>
 *   <li><b>Observability</b> — structured logs (MDC + request ID), Micrometer metrics exported
 *       to Prometheus, distributed traces exported via OpenTelemetry to Tempo.</li>
 *   <li><b>Resilience</b> — Resilience4j circuit breaker + retry on external HTTP calls;
 *       rate limiting via Bucket4j token-bucket algorithm; idempotent POST via in-memory cache.</li>
 *   <li><b>Messaging</b> — two Kafka patterns: fire-and-forget async events and
 *       synchronous request-reply using {@code ReplyingKafkaTemplate}.</li>
 *   <li><b>AI</b> — Spring AI + Ollama for on-demand customer bio generation.</li>
 *   <li><b>Security</b> — stateless JWT authentication (JJWT), Spring Security filter chain.</li>
 *   <li><b>Scheduling</b> — distributed ShedLock to prevent duplicate scheduler execution
 *       when multiple instances run simultaneously.</li>
 * </ul>
 *
 * <p>{@code @SpringBootApplication} is a meta-annotation that activates:
 * {@code @Configuration}, {@code @EnableAutoConfiguration}, and {@code @ComponentScan}.
 * Auto-configuration picks up all starter dependencies present on the classpath
 * (Actuator, JPA, Kafka, OpenTelemetry, etc.) and wires them automatically.
 */
@SpringBootApplication
@EnableCaching                                             // activates @Cacheable / @CacheEvict / @CachePut on beans
@EnableScheduling                                          // activates @Scheduled method processing
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")      // [ShedLock] distributed lock for schedulers
public class MiradorApplication {

    /**
     * Bootstraps the Spring application context and starts the embedded Tomcat server.
     * All beans declared via {@code @Bean}, {@code @Component}, {@code @Service}, etc.
     * are discovered through the component scan rooted at this package.
     */
    public static void main(String[] args) {
        SpringApplication.run(MiradorApplication.class, args);
    }
}
