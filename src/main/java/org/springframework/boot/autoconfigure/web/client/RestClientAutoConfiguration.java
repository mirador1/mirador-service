package org.springframework.boot.autoconfigure.web.client;

/**
 * Compatibility shim for Spring AI 1.0.0-M6 running on Spring Boot 4.
 *
 * <p>Spring AI M6 was built against Spring Boot 3.x and its auto-configuration
 * references {@code RestClientAutoConfiguration} via {@code @ImportAutoConfiguration}.
 * In Spring Boot 4, this class was removed/restructured. The reference happens at
 * annotation-processing time (before any bean lifecycle), so it cannot be suppressed
 * via {@code spring.autoconfigure.exclude}.
 *
 * <p>This empty shim satisfies the class reference so that Spring AI's
 * {@code OllamaAutoConfiguration} can be loaded. The actual {@code RestClient.Builder}
 * bean is provided by Spring Boot 4's own autoconfiguration.
 *
 * <p>Remove this class once Spring AI releases a version compatible with Spring Boot 4.
 */
// S2094: intentionally empty — the class exists only so that Spring AI's
// @ImportAutoConfiguration(RestClientAutoConfiguration.class) resolves at
// annotation-processing time. Making it an interface would break that.
@SuppressWarnings("java:S2094")
public class RestClientAutoConfiguration {
}
