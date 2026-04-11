package com.example.springapi.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configures Spring Framework 7's native API versioning for the application.
 *
 * <h3>How Spring 7 API versioning works</h3>
 * <p>Spring Framework 7 introduced first-class API versioning support via
 * {@link WebMvcConfigurer#configureApiVersioning(ApiVersionConfigurer)}.
 * The framework resolves a version string from each incoming request and uses it
 * to select the matching {@code @RequestMapping(version = "...")} handler.
 *
 * <h3>Version resolution strategy: request header</h3>
 * <p>This application resolves the version from the {@code X-API-Version} header.
 * Other built-in resolvers include:
 * <ul>
 *   <li>{@code useQueryParam("v")} — e.g. {@code GET /customers?v=2.0}</li>
 *   <li>{@code usePathSegment(0)} — e.g. {@code GET /2.0/customers}</li>
 *   <li>{@code useMediaTypeParameter(...)} — e.g. {@code Accept: application/json;version=2.0}</li>
 * </ul>
 *
 * <h3>Default version</h3>
 * <p>Requests without an {@code X-API-Version} header are assigned version {@code 1.0}.
 * This ensures backward compatibility: existing clients that are unaware of versioning
 * continue to receive the v1 response shape without any changes on their side.
 *
 * <h3>Version matching semantics on the handler side</h3>
 * <ul>
 *   <li>{@code version = "1.0"} — matches exactly version 1.0.</li>
 *   <li>{@code version = "2.0+"} — matches 2.0 and any higher version (baseline semantics),
 *       so v3 clients automatically receive the v2 response until a v3 handler is defined.</li>
 * </ul>
 *
 * <p>See {@link com.example.springapi.customer.CustomerController} for the versioned endpoints.
 *
 * [Spring 7 / Spring Boot 4] — {@link ApiVersionConfigurer} is new in Spring Framework 7.0.
 */
@Configuration
public class ApiVersionConfig implements WebMvcConfigurer {

    /**
     * Resolves the API version from the {@code X-API-Version} request header.
     * Requests without the header fall back to default version {@code 1.0}.
     *
     * <p>The {@link org.springframework.web.accept.SemanticApiVersionParser} (default) parses
     * the header value as a semantic version (major.minor), enabling the {@code "2.0+"} baseline
     * syntax in {@code @RequestMapping(version = ...)}.
     */
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                // Read the version from the X-API-Version header
                .useRequestHeader("X-API-Version")
                // Fall back to v1 for clients that don't send the header
                .setDefaultVersion("1.0");
    }
}
