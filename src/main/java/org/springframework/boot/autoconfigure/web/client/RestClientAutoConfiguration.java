package org.springframework.boot.autoconfigure.web.client;

/**
 * Compatibility shim for Spring AI running on Spring Boot 4.
 *
 * <p>Even the 1.1.x GA line of Spring AI still annotates its
 * {@code OllamaApiAutoConfiguration} with
 * {@code @AutoConfiguration(after = RestClientAutoConfiguration.class)} pointing
 * at the Spring Boot 3 package. In Spring Boot 4 that class was moved to
 * {@code org.springframework.boot.webmvc.autoconfigure.client.RestClientAutoConfiguration};
 * the reference is resolved at annotation-processing time, before any bean
 * lifecycle, so it cannot be suppressed via {@code spring.autoconfigure.exclude}.
 *
 * <p>This empty placeholder satisfies the class reference so the Spring AI
 * auto-configuration loads. The actual {@code RestClient.Builder} bean still
 * comes from Spring Boot 4's own auto-configuration.
 *
 * <p>Remove once Spring AI updates its imports to the Spring Boot 4 package.
 */
// S2094: intentionally empty — the class exists only so that Spring AI's
// @AutoConfiguration(after = RestClientAutoConfiguration.class) resolves at
// annotation-processing time. Making it an interface would break that.
@SuppressWarnings("java:S2094")
public class RestClientAutoConfiguration {
}
