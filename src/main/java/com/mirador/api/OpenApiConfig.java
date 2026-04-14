package com.mirador.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Global OpenAPI / Swagger UI configuration.
 *
 * <p>Registers:
 * <ul>
 *   <li>API metadata (title, description, version, contact)</li>
 *   <li>Bearer JWT security scheme — the "Authorize" button in Swagger UI accepts a raw JWT
 *       (without the "Bearer " prefix); SpringDoc prepends it automatically.</li>
 *   <li>A global security requirement so all endpoints show the lock icon by default;
 *       permit-all endpoints (login, refresh, stream, demo) override it via
 *       {@code @SecurityRequirements({})} on the method.</li>
 *   <li>Server entry pointing at the local dev instance.</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        final String securitySchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Customer Observability API")
                        .description("""
                                Full-stack observability and management API built with **Spring Boot 4** and **Java 25**.

                                ## Authentication
                                1. Call `POST /auth/login` with `{"username":"admin","password":"admin"}`
                                2. Copy the `accessToken` from the response
                                3. Click **Authorize** (top right) and paste the token (without the `Bearer ` prefix)

                                ## API Versioning
                                Endpoints that support multiple versions use the `X-API-Version` header:
                                - `X-API-Version: 1.0` → v1 response (no `createdAt`)
                                - `X-API-Version: 2.0` → v2 response (includes `createdAt`)
                                - Omitting the header defaults to **v1**.

                                ## Resilience patterns demonstrated
                                - **Rate limiting**: 100 req/min per IP via Bucket4j → `429 Too Many Requests`
                                - **Idempotency**: repeat a `POST /customers` with the same `Idempotency-Key` to get a cached response
                                - **Circuit breaker**: `/bio` uses Resilience4j on the Ollama LLM call
                                - **Kafka request-reply**: `/enrich` blocks until the consumer replies or times out → `504`

                                ## Observability
                                Every endpoint emits Micrometer metrics (→ Prometheus) and OpenTelemetry spans (→ Tempo via LGTM).
                                """)
                        .version("2.0")
                        .contact(new Contact()
                                .name("Customer Observability UI")
                                .url("http://localhost:4200"))
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .addServersItem(new Server()
                        .url("http://localhost:8080")
                        .description("Local development server"))
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName, new SecurityScheme()
                                .name(securitySchemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the `accessToken` from `POST /auth/login`. Do not include the `Bearer ` prefix.")));
    }
}
