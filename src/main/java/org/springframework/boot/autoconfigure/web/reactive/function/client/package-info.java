/**
 * Compatibility shim for Spring AI on Spring Boot 4.
 *
 * <p>See {@link org.springframework.boot.autoconfigure.web.client} for the full rationale.
 * In Spring Boot 4, {@code WebClientAutoConfiguration} was moved to the reactive module
 * but Spring AI's {@code @AutoConfiguration(after = ...)} annotation still points at the
 * Spring Boot 3 package path.
 */
package org.springframework.boot.autoconfigure.web.reactive.function.client;
