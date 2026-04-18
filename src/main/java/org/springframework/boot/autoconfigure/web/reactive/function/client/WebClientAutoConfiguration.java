package org.springframework.boot.autoconfigure.web.reactive.function.client;

/**
 * Compatibility shim for Spring AI running on Spring Boot 4.
 *
 * <p>Even the 1.1.x GA line of Spring AI still annotates its
 * {@code OllamaApiAutoConfiguration} with
 * {@code @AutoConfiguration(after = WebClientAutoConfiguration.class)} pointing
 * at the Spring Boot 3 package. The reference is resolved at annotation-processing
 * time, so it cannot be suppressed via {@code spring.autoconfigure.exclude}.
 *
 * <p>This empty placeholder satisfies the class reference so the Spring AI
 * auto-configuration loads. The actual {@code WebClient.Builder} bean still
 * comes from Spring Boot 4's own auto-configuration.
 *
 * <p>Remove once Spring AI updates its imports to the Spring Boot 4 package.
 */
// S2094: intentionally empty — annotation-processing-time reference target
// for Spring AI's @AutoConfiguration(after = ...). Making it an interface
// would break that.
@SuppressWarnings("java:S2094")
public class WebClientAutoConfiguration {
}
