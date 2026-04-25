package com.mirador.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

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
                        // The `name` setter is intentionally OMITTED here: per the OpenAPI 3.x
                        // spec, `name` is only valid on `apiKey` security schemes (where it
                        // designates the header/query/cookie parameter carrying the key). For
                        // `http` schemes the name comes from the outer `addSecuritySchemes(key,
                        // …)` call. swagger-models silently lets you call .name() on an HTTP
                        // scheme and serialises it, but Spectral's `oas3-schema` rule rejects
                        // the resulting object with `unevaluated properties: name` — the only
                        // remaining error after ADR-0037 Path B. Removing the call closes
                        // TASKS.md → "openapi-lint flip allow_failure: false".
                        .addSecuritySchemes(securitySchemeName, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the `accessToken` from `POST /auth/login`. Do not include the `Bearer ` prefix.")));
    }

    /**
     * Strips OpenAPI noise that springdoc 3.0.x emits and that Spectral catches as
     * spec violations — implements ADR-0037 "Path B" so the two rules
     * {@code oas3-valid-schema-example} and {@code oas3-valid-media-example} can
     * be re-enabled in {@code .spectral.yaml}.
     *
     * <p>What springdoc emits and why it is wrong (verified on the live
     * {@code /v3/api-docs} response, springdoc 3.0.3 + swagger-models 2.2.47):
     * <ul>
     *   <li>{@code default: null} on every parent object schema (e.g. CustomerDto).
     *       Spectral validates this against {@code type: object} and rejects it as
     *       "default property type must be object". The Java field actually holds
     *       a Jackson MissingNode token (not Java null) which the SchemaSerializer
     *       turns into {@code "default": null}. Detecting the token by class name
     *       and clearing {@code defaultSetFlag} suppresses the field entirely.</li>
     *   <li>{@code default: ""} on every primitive property (integer, format=email,
     *       format=date-time, …). Empty string is not a valid integer / e-mail /
     *       date-time, so Spectral flags every one.</li>
     *   <li>{@code example: 42} (integer JSON literal) at the parameter level
     *       while {@code schema.type} is {@code string} (springdoc maps
     *       {@code @PathVariable Long} to a string-typed schema). The parameter
     *       example must conform to the schema type, hence the conflict.</li>
     * </ul>
     *
     * <p>Path A (ADR-0037) would mean ~50 per-DTO {@code @Schema} annotations and
     * is brittle — every new field becomes a maintenance task. Path B (this bean)
     * post-processes the generated tree once and covers all current and future DTOs.
     * Path C (wait for upstream) was deferred indefinitely.
     *
     * <p>The customizer is intentionally conservative — it only touches values
     * that are demonstrably noise (empty string default on a non-string schema,
     * MissingNode/null token on any schema, parameter-level example with a type
     * mismatch). Real, hand-authored {@code @Schema(defaultValue=…)} or
     * {@code example=…} values are preserved when they validate.
     */
    @Bean
    public OpenApiCustomizer openApiSchemaSanitizer() {
        return openApi -> {
            sanitizeComponentSchemas(openApi);
            sanitizePathParameters(openApi);
        };
    }

    /** Walks every schema under {@code components.schemas} and drops invalid defaults. */
    private static void sanitizeComponentSchemas(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
            sanitizeSchemaTree(schema);
        }
    }

    /**
     * Recursively cleans a schema and its property children. Only descends into
     * {@code properties}; arrays / oneOf / anyOf are left alone because they do
     * not show up in the spec we generate today and changing more would risk
     * false positives.
     */
    private static void sanitizeSchemaTree(Schema<?> schema) {
        if (schema == null) {
            return;
        }
        dropInvalidDefault(schema);
        @SuppressWarnings("rawtypes")
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null) {
            for (Schema<?> prop : properties.values()) {
                sanitizeSchemaTree(prop);
            }
        }
    }

    /**
     * Drops a {@code default} value when it cannot possibly be valid for the
     * schema type. Three patterns springdoc actually emits:
     * <ul>
     *   <li>{@code default = MissingNode} (Jackson 2.x token) on every parent
     *       schema synthesized from a @Schema annotation — surfaces as
     *       {@code "default": null} in the JSON output.</li>
     *   <li>{@code default = NullNode} (Jackson 2.x token) — same effect.</li>
     *   <li>{@code default = ""} (empty string) on a non-string schema —
     *       e.g. integer id, format=email, format=date-time.</li>
     * </ul>
     * Anything else (a real {@code @Schema(defaultValue="42")} on an integer
     * field) is left untouched.
     */
    private static void dropInvalidDefault(Schema<?> schema) {
        Object defaultValue = schema.getDefault();
        String type = effectiveType(schema);

        // Treat Jackson MissingNode / NullNode as semantically null. springdoc
        // 3.0.x stores `_default = MissingNode.instance` for every schema
        // synthesized from a @Schema annotation that does NOT declare a
        // defaultValue. The SchemaSerializer (see swagger-core 2.2.x source)
        // emits `"default": null` whenever defaultSetFlag is true AND the
        // value is null — and a MissingNode token has the same effect via a
        // separate code path. Identifying by class name avoids hard-linking
        // against a specific Jackson version (Spring Boot 4 ships both
        // Jackson 2.x via swagger-models and Jackson 3.x via spring-jackson
        // on the same classpath).
        if (defaultValue != null) {
            String cn = defaultValue.getClass().getName();
            if (cn.endsWith(".MissingNode") || cn.endsWith(".NullNode")) {
                schema.setDefault(null);
                return;
            }
        }

        // Case 1: getDefault() is null but defaultSetFlag is true. Same root
        // cause as above but caught earlier in the springdoc pipeline.
        // Clearing the flag suppresses the noise without reflection.
        if (defaultValue == null) {
            return;
        }

        // Case 2: empty string default on a non-string schema (integer, number,
        // boolean, object, array…). Never a valid instance of the declared type.
        if (defaultValue instanceof String s && s.isEmpty() && type != null && !"string".equals(type)) {
            schema.setDefault(null);
            return;
        }

        // Case 3: empty string default on a string schema that declares a strict
        // format (email, uri, date-time, uuid…). Empty string fails the format,
        // which Spectral validates.
        if (defaultValue instanceof String s && s.isEmpty()
                && "string".equals(type) && schema.getFormat() != null) {
            schema.setDefault(null);
        }
    }

    /**
     * Returns the effective single type for a schema. swagger-models 2.2.x
     * stores types in two places: the OpenAPI 3.0 singular {@code type} field
     * AND the OpenAPI 3.1 plural {@code types} set. springdoc 3.0.x can
     * populate either depending on the spec version; the JSON serializer
     * collapses a single-element set to a singular type, so for our purposes
     * a one-element {@code types} set is equivalent to {@code type}.
     */
    private static String effectiveType(Schema<?> schema) {
        String t = schema.getType();
        if (t != null) {
            return t;
        }
        if (schema.getTypes() != null && schema.getTypes().size() == 1) {
            return schema.getTypes().iterator().next();
        }
        return null;
    }

    /**
     * Walks every {@code parameters} list under {@code paths.[path].[verb]} and
     * normalises parameter-level examples that do not conform to the parameter's
     * schema type. The schema itself is not re-typed here (springdoc miscategorises
     * {@code @PathVariable Long} as string-typed — fixing that needs annotation
     * work covered in Path A); the goal is just to make Spectral pass without
     * lying about the contract.
     */
    private static void sanitizePathParameters(OpenAPI openApi) {
        if (openApi.getPaths() == null) {
            return;
        }
        for (PathItem pathItem : openApi.getPaths().values()) {
            if (pathItem == null) {
                continue;
            }
            sanitizeOperationParams(pathItem.getGet());
            sanitizeOperationParams(pathItem.getPost());
            sanitizeOperationParams(pathItem.getPut());
            sanitizeOperationParams(pathItem.getPatch());
            sanitizeOperationParams(pathItem.getDelete());
            sanitizeOperationParams(pathItem.getHead());
            sanitizeOperationParams(pathItem.getOptions());
            sanitizeOperationParams(pathItem.getTrace());
        }
    }

    private static void sanitizeOperationParams(Operation op) {
        if (op == null || op.getParameters() == null) {
            return;
        }
        for (Parameter param : op.getParameters()) {
            // First, sanitize the parameter's own schema (same defaults rule).
            if (param.getSchema() != null) {
                sanitizeSchemaTree(param.getSchema());
            }
            Object example = param.getExample();
            if (example == null) {
                continue;
            }
            String schemaType = param.getSchema() != null ? effectiveType(param.getSchema()) : null;
            Object coerced = coerceExample(example, schemaType);
            if (coerced == null) {
                // Type mismatch with no safe coercion — drop the example
                // rather than emit invalid OAS. Better no example than a
                // wrong one.
                param.setExample(null);
            } else if (!coerced.equals(example)) {
                // Coercion changed the value (e.g. 42 → "42" for a string
                // schema): move the corrected example onto the schema, where
                // Spectral validates it against the type. The parameter-level
                // example is dropped so we do not keep both in sync.
                // Note: Schema#setExample also flips exampleSetFlag = true,
                // which is what we want — the value is real, not a placeholder.
                param.setExample(null);
                if (param.getSchema() != null) {
                    param.getSchema().setExample(coerced);
                }
            }
            // If coerced.equals(example), the example already matches the
            // schema type — leave the parameter-level example alone.
        }
    }

    /**
     * Returns the example coerced to the schema type, or {@code null} when
     * coercion is not safe. Handles the three combinations springdoc actually
     * produces today; everything else falls through to "leave as-is".
     */
    private static Object coerceExample(Object example, String schemaType) {
        if (schemaType == null) {
            return example;          // unknown schema type — best-effort, keep example
        }
        boolean alreadyValid = switch (schemaType) {
            case "string" -> example instanceof String;
            case "integer" -> example instanceof Integer || example instanceof Long;
            case "number" -> example instanceof Number;
            case "boolean" -> example instanceof Boolean;
            default -> true;        // object/array — do not second-guess
        };
        if (alreadyValid) {
            return example;
        }
        try {
            if ("string".equals(schemaType)
                    && (example instanceof Number || example instanceof Boolean)) {
                return example.toString();
            }
            if ("integer".equals(schemaType) && example instanceof String s) {
                return Long.parseLong(s);
            }
            if ("number".equals(schemaType) && example instanceof String s) {
                return Double.parseDouble(s);
            }
            if ("boolean".equals(schemaType) && example instanceof String s
                    && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false"))) {
                return Boolean.parseBoolean(s);
            }
        } catch (NumberFormatException ignored) {
            // Not parseable — fall through to drop.
        }
        // No safe coercion (e.g. Map example on integer schema) — drop.
        return null;
    }
}
