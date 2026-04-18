/**
 * Compatibility shims for Spring AI on Spring Boot 4.
 *
 * <p>Classes in this package shadow auto-configuration classes that were removed or
 * restructured in Spring Boot 4. Spring AI (checked through 1.1.4 GA) still references
 * them at annotation-processing time via {@code @AutoConfiguration(after = ...)},
 * before any bean lifecycle — which means {@code spring.autoconfigure.exclude} cannot
 * suppress them.
 *
 * <p>The shims are empty: they exist only to satisfy the class reference so that Spring
 * AI's {@code OllamaApiAutoConfiguration} loads without a {@code ClassNotFoundException}.
 * The actual beans are provided by Spring Boot 4's own auto-configuration.
 *
 * <p><strong>Remove this package</strong> once Spring AI updates the offending
 * {@code @AutoConfiguration(after)} references to the Spring Boot 4 package
 * (org.springframework.boot.webmvc.autoconfigure.client.*).
 *
 * @see org.springframework.boot.autoconfigure.web.reactive.function.client
 */
package org.springframework.boot.autoconfigure.web.client;
