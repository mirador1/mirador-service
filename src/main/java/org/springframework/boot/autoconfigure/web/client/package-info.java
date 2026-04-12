/**
 * Compatibility shims for Spring AI 1.0.0-M6 on Spring Boot 4.
 *
 * <p>Classes in this package shadow auto-configuration classes that were removed or
 * restructured in Spring Boot 4. Spring AI M6 was built against Spring Boot 3.x and
 * references them at annotation-processing time via {@code @ImportAutoConfiguration},
 * before any bean lifecycle — which means {@code spring.autoconfigure.exclude} cannot
 * suppress them.
 *
 * <p>The shims are empty: they exist only to satisfy the class reference so that Spring
 * AI's {@code OllamaAutoConfiguration} loads without a {@code ClassNotFoundException}.
 * The actual beans are provided by Spring Boot 4's own auto-configuration.
 *
 * <p><strong>Remove this package</strong> once Spring AI ships a release compatible
 * with Spring Boot 4 (expected in the 1.0 GA cycle).
 *
 * @see org.springframework.boot.autoconfigure.web.reactive.function.client
 */
package org.springframework.boot.autoconfigure.web.client;
