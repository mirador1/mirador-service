package com.mirador.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Paths;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenApiConfig} — the Swagger UI metadata bean
 * and the schema-sanitizer customizer that implements ADR-0037 Path B.
 *
 * <p>Pinned contracts:
 *   - customOpenAPI() returns a populated OpenAPI with title + version +
 *     bearer auth security scheme (Swagger UI's "Authorize" button)
 *   - openApiSchemaSanitizer drops invalid defaults (MissingNode, empty
 *     string on non-string schemas) so Spectral's oas3-valid-schema-example
 *     rule passes — these surface as 50+ false positives without the
 *     sanitizer.
 *   - Parameter examples coerced to schema type when safe (e.g. 42 → "42"
 *     for string schema), dropped when not (e.g. Map → integer)
 */
// eslint-disable-next-line max-lines-per-function (Java has no per-function limit enforced)
class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void customOpenAPI_setsTitleVersionAndBearerAuthScheme() {
        // Pinned: the title appears in the Swagger UI tab + browser
        // address bar history. Version is what /v3/api-docs.version
        // returns and what client-codegen tools key off. Both are part
        // of the public contract.
        OpenAPI api = config.customOpenAPI();

        assertThat(api.getInfo().getTitle()).isEqualTo("Customer Observability API");
        assertThat(api.getInfo().getVersion()).isEqualTo("2.0");
        // License + contact populated (UI shows them in the footer)
        assertThat(api.getInfo().getLicense().getName()).isEqualTo("MIT");
        assertThat(api.getInfo().getContact().getName()).isEqualTo("Customer Observability UI");
    }

    @Test
    void customOpenAPI_registersBearerAuthSecurityScheme() {
        // Pinned: Swagger UI's "Authorize" button reads the schemes from
        // components.securitySchemes. Missing or renamed scheme breaks
        // every protected endpoint from being callable in the UI.
        OpenAPI api = config.customOpenAPI();

        Components components = api.getComponents();
        assertThat(components).isNotNull();
        SecurityScheme bearer = components.getSecuritySchemes().get("bearerAuth");
        assertThat(bearer).isNotNull();
        assertThat(bearer.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(bearer.getScheme()).isEqualTo("bearer");
        assertThat(bearer.getBearerFormat()).isEqualTo("JWT");
    }

    @Test
    void customOpenAPI_addsLocalServerEntry() {
        // Pinned: server URL is what Swagger's "Try it out" button targets.
        // Wrong URL = test calls go to nowhere.
        OpenAPI api = config.customOpenAPI();

        assertThat(api.getServers()).hasSize(1);
        assertThat(api.getServers().get(0).getUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void customOpenAPI_appliesGlobalSecurityRequirementSoLockIconShows() {
        // Pinned: the global SecurityRequirement makes Swagger UI render
        // a lock icon next to every endpoint by default. Permit-all
        // endpoints (login/refresh/stream) override via
        // @SecurityRequirements({}) on the method. A regression here
        // would render every endpoint as "open", misleading API users.
        OpenAPI api = config.customOpenAPI();

        assertThat(api.getSecurity()).isNotEmpty();
        assertThat(api.getSecurity().get(0)).containsKey("bearerAuth");
    }

    @Test
    void schemaSanitizer_dropsEmptyStringDefaultOnIntegerSchema() {
        // Pinned: springdoc emits `default: ""` on every primitive
        // property — Spectral validates this against type=integer and
        // flags it. The customizer detects empty string + non-string
        // schema and clears it. Without this, ~50 false positives.
        OpenApiCustomizer customizer = config.openApiSchemaSanitizer();
        OpenAPI api = openApiWithSchema("Customer", schemaWithDefault("integer", ""));

        customizer.customise(api);

        Schema<?> schema = api.getComponents().getSchemas().get("Customer");
        assertThat(schema.getDefault()).isNull();
    }

    @Test
    void schemaSanitizer_keepsRealUserDeclaredIntegerDefault() {
        // Pinned: a hand-authored @Schema(defaultValue="42") on an
        // integer field MUST be preserved — only INVALID defaults
        // (empty string, MissingNode) get dropped. The line between
        // "noise" and "intent" is exactly this test.
        OpenApiCustomizer customizer = config.openApiSchemaSanitizer();
        OpenAPI api = openApiWithSchema("Customer", schemaWithDefault("integer", 42));

        customizer.customise(api);

        Schema<?> schema = api.getComponents().getSchemas().get("Customer");
        assertThat(schema.getDefault()).isEqualTo(42);
    }

    @Test
    void schemaSanitizer_dropsEmptyStringDefaultOnFormattedStringSchema() {
        // Pinned: empty string is also invalid on a formatted string
        // (email, uri, date-time, uuid) — fails the format constraint.
        // The sanitizer drops it. type=string + format=null is left alone
        // (empty string is a valid string).
        OpenApiCustomizer customizer = config.openApiSchemaSanitizer();
        Schema<?> emailSchema = schemaWithDefault("string", "");
        emailSchema.setFormat("email");
        OpenAPI api = openApiWithSchema("User", emailSchema);

        customizer.customise(api);

        Schema<?> schema = api.getComponents().getSchemas().get("User");
        assertThat(schema.getDefault()).isNull();
    }

    @Test
    void schemaSanitizer_keepsEmptyStringDefaultOnPlainStringSchema() {
        // Pinned: empty string IS a valid string — only fails with a
        // format constraint. This test pins the negative case to
        // prevent over-sanitization.
        OpenApiCustomizer customizer = config.openApiSchemaSanitizer();
        OpenAPI api = openApiWithSchema("Notes", schemaWithDefault("string", ""));

        customizer.customise(api);

        Schema<?> schema = api.getComponents().getSchemas().get("Notes");
        assertThat(schema.getDefault()).isEqualTo("");
    }

    @Test
    void schemaSanitizer_handlesNullComponentsGracefully() {
        // Defensive: an OpenAPI tree with no components shouldn't NPE.
        // Pre-customizer state is sometimes empty in tests.
        OpenApiCustomizer customizer = config.openApiSchemaSanitizer();
        OpenAPI api = new OpenAPI();

        customizer.customise(api); // must not throw
    }

    @Test
    void schemaSanitizer_coercesIntegerExampleToStringWhenSchemaTypeIsString() {
        // Pinned: springdoc emits parameter example=42 (integer) on a
        // schema declared as type=string (because @PathVariable Long
        // gets mapped to string by springdoc). The sanitizer coerces
        // 42 → "42" and moves it to the schema. Without this, Spectral's
        // oas3-valid-media-example rule fails for every Long path param.
        OpenApiCustomizer customizer = config.openApiSchemaSanitizer();
        Parameter param = new Parameter()
                .name("id")
                .schema(new Schema<>().type("string"))
                .example(42);
        OpenAPI api = openApiWithGetParam("/customers/{id}", param);

        customizer.customise(api);

        Parameter customised = api.getPaths().get("/customers/{id}").getGet().getParameters().get(0);
        assertThat(customised.getExample()).isNull();          // moved off the parameter
        assertThat(customised.getSchema().getExample()).isEqualTo("42"); // onto the schema
    }

    @Test
    void schemaSanitizer_dropsExampleWhenCoercionImpossible() {
        // Pinned: a Map-typed example on an integer schema can't be
        // coerced safely → drop it rather than emit invalid OAS. The
        // comment in the source says "better no example than a wrong one".
        OpenApiCustomizer customizer = config.openApiSchemaSanitizer();
        Parameter param = new Parameter()
                .name("count")
                .schema(new Schema<>().type("integer"))
                .example(Map.of("nested", "object"));
        OpenAPI api = openApiWithGetParam("/x", param);

        customizer.customise(api);

        Parameter customised = api.getPaths().get("/x").getGet().getParameters().get(0);
        assertThat(customised.getExample()).isNull();
    }

    /** Helper: an OpenAPI with a single named schema in components. */
    private static OpenAPI openApiWithSchema(String name, Schema<?> schema) {
        OpenAPI api = new OpenAPI();
        Components components = new Components();
        components.addSchemas(name, schema);
        api.setComponents(components);
        return api;
    }

    /** Helper: a Schema with a primitive type and a default value. */
    private static Schema<?> schemaWithDefault(String type, Object defaultValue) {
        Schema<Object> schema = new Schema<>();
        schema.setType(type);
        schema.setDefault(defaultValue);
        return schema;
    }

    /** Helper: an OpenAPI with one GET path containing one parameter. */
    private static OpenAPI openApiWithGetParam(String path, Parameter param) {
        OpenAPI api = new OpenAPI();
        Paths paths = new Paths();
        Operation get = new Operation();
        get.addParametersItem(param);
        PathItem pathItem = new PathItem().get(get);
        paths.addPathItem(path, pathItem);
        api.setPaths(paths);
        return api;
    }
}
