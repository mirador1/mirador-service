package com.mirador.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the OpenApiCustomizer bean exposed by {@link OpenApiConfig}
 * (see {@code openApiSchemaSanitizer()}). Each test mirrors a concrete springdoc
 * output pattern that previously failed Spectral's
 * {@code oas3-valid-schema-example} or {@code oas3-valid-media-example} rule —
 * implementing ADR-0037 Path B. Pure POJO manipulation, no Spring context.
 */
class OpenApiSchemaSanitizerTest {

    private final OpenApiCustomizer customizer = new OpenApiConfig().openApiSchemaSanitizer();

    @Test
    void dropsNullDefaultOnObjectSchema() {
        // springdoc emits `default: null` on every parent schema (CustomerDto,
        // CreateCustomerRequest…). The Java _default field holds a NullNode
        // token that the JSON serializer prints as `null`.
        Schema<Object> dtoSchema = new ObjectSchema().type("object");
        dtoSchema.setDefault(null);     // explicit no-op to match the JsonNull case
        OpenAPI openApi = openApiWithSchema("CustomerDto", dtoSchema);

        customizer.customise(openApi);

        assertThat(dtoSchema.getDefault()).isNull();
    }

    @Test
    void dropsEmptyStringDefaultOnIntegerProperty() {
        // springdoc emits `default: ""` on every numeric property. Empty
        // string is never a valid integer, so the rule fires.
        Schema<?> idSchema = new IntegerSchema().format("int64");
        idSchema.setDefault("");
        Schema<Object> parent = new ObjectSchema();
        parent.setProperties(Map.of("id", idSchema));
        OpenAPI openApi = openApiWithSchema("CustomerDto", parent);

        customizer.customise(openApi);

        assertThat(idSchema.getDefault()).isNull();
    }

    @Test
    void dropsEmptyStringDefaultOnFormattedStringProperty() {
        // Same noise on string fields with a strict format (email, date-time,
        // uri, uuid…). Empty string fails the format check.
        Schema<?> emailSchema = new StringSchema().format("email");
        emailSchema.setDefault("");
        Schema<Object> parent = new ObjectSchema();
        parent.setProperties(Map.of("email", emailSchema));
        OpenAPI openApi = openApiWithSchema("CreateCustomerRequest", parent);

        customizer.customise(openApi);

        assertThat(emailSchema.getDefault()).isNull();
    }

    @Test
    void preservesEmptyStringDefaultOnPlainStringProperty() {
        // Plain `type: string` with no format — empty string IS valid as a
        // default, do not touch.
        Schema<?> nameSchema = new StringSchema();
        nameSchema.setDefault("");
        Schema<Object> parent = new ObjectSchema();
        parent.setProperties(Map.of("name", nameSchema));
        OpenAPI openApi = openApiWithSchema("CreateCustomerRequest", parent);

        customizer.customise(openApi);

        assertThat(nameSchema.getDefault()).isEqualTo("");
    }

    @Test
    void preservesValidIntegerDefault() {
        // A real `@Schema(defaultValue = "10")` on an integer must survive
        // the sanitizer.
        Schema<?> sizeSchema = new IntegerSchema().format("int32");
        sizeSchema.setDefault(10);
        Schema<Object> parent = new ObjectSchema();
        parent.setProperties(Map.of("size", sizeSchema));
        OpenAPI openApi = openApiWithSchema("PageRequest", parent);

        customizer.customise(openApi);

        assertThat(sizeSchema.getDefault()).isEqualTo(10);
    }

    @Test
    void coercesIntegerExampleOnStringSchemaToString() {
        // Real failure pattern: `@Parameter(example = "42") @PathVariable Long id`
        // → springdoc emits parameter.example = 42 (integer) but
        // schema.type = "string". Coerce to "42" and move under schema.example
        // so Spectral validates against schema.type.
        Parameter idParam = new Parameter()
                .name("id")
                .in("path")
                .schema(new StringSchema())
                .example(42);
        OpenAPI openApi = openApiWithParameter("/customers/{id}", idParam);

        customizer.customise(openApi);

        assertThat(idParam.getExample()).isNull();
        assertThat(idParam.getSchema().getExample()).isEqualTo("42");
    }

    @Test
    void preservesStringExampleOnStringSchema() {
        // No type mismatch → leave alone.
        Parameter actionParam = new Parameter()
                .name("action")
                .in("query")
                .schema(new StringSchema())
                .example("LOGIN_FAILED");
        OpenAPI openApi = openApiWithParameter("/audit", actionParam);

        customizer.customise(openApi);

        assertThat(actionParam.getExample()).isEqualTo("LOGIN_FAILED");
    }

    @Test
    void dropsMapExampleOnIntegerSchema() {
        // No safe coercion possible — drop rather than emit invalid OAS.
        Parameter weirdParam = new Parameter()
                .name("count")
                .in("query")
                .schema(new IntegerSchema())
                .example(Map.of("nested", "value"));
        OpenAPI openApi = openApiWithParameter("/weird", weirdParam);

        customizer.customise(openApi);

        assertThat(weirdParam.getExample()).isNull();
        assertThat(weirdParam.getSchema().getExample()).isNull();
    }

    @Test
    void coercesParseableStringExampleToInteger() {
        // Symmetric case: example="3" but schema.type=integer.
        Parameter idParam = new Parameter()
                .name("id")
                .in("path")
                .schema(new IntegerSchema().format("int64"))
                .example("3");
        OpenAPI openApi = openApiWithParameter("/customers/{id}/todos", idParam);

        customizer.customise(openApi);

        assertThat(idParam.getExample()).isNull();
        // IntegerSchema#cast downcasts to Integer when the value fits in int32 —
        // we set 3L but the schema stores it as Integer(3). Keep the test
        // assertion in sync with that cast behavior rather than fight it.
        assertThat(idParam.getSchema().getExample()).isEqualTo(3);
    }

    @Test
    void cleansParameterSchemaDefaultsToo() {
        // springdoc also emits `default: ""` inside the parameter schema (not
        // just at the top level). The sanitizer must walk into params too.
        Schema<?> paramSchema = new IntegerSchema();
        paramSchema.setDefault("");
        Parameter idParam = new Parameter().name("id").in("path").schema(paramSchema).example(42);
        OpenAPI openApi = openApiWithParameter("/customers/{id}", idParam);

        customizer.customise(openApi);

        assertThat(paramSchema.getDefault()).isNull();
        // 42 is a valid integer for an integer schema → kept as-is.
        assertThat(idParam.getExample()).isEqualTo(42);
    }

    @Test
    void handlesNullComponentsAndPathsGracefully() {
        // Defensive: an empty OpenAPI must not NPE.
        customizer.customise(new OpenAPI());
    }

    @Test
    void treatsJacksonMissingNodeDefaultAsNull() {
        // springdoc 3.0.x parks Jackson MissingNode.instance into _default for
        // every parent schema synthesized from a @Schema annotation that has
        // no defaultValue. With defaultSetFlag=true and getDefault() returning
        // a non-null token, the SchemaSerializer ends up emitting `default: null`
        // anyway. Detecting MissingNode and clearing the flag is the actual
        // root-cause fix for the most common ADR-0037 spectator failure
        // (CustomerDto.default, EnrichedCustomerDto.default…).
        Schema<Object> dto = new ObjectSchema().type("object");
        dto.setDefault(com.fasterxml.jackson.databind.node.MissingNode.getInstance());
        dto.setDefaultSetFlag(true);
        OpenAPI openApi = openApiWithSchema("CustomerDto", dto);

        customizer.customise(openApi);

        assertThat(dto.getDefaultSetFlag()).isFalse();
    }

    @Test
    void usesTypesSetWhenSingularTypeIsNull() {
        // springdoc 3.0.x sometimes populates the OpenAPI 3.1 plural `types`
        // set instead of the singular `type` field, especially on schemas
        // generated from @Schema-annotated DTOs (CustomerDto…). Without
        // falling back to types.iterator().next(), the customizer would
        // miss those schemas and leave `default: ""` in place. Regression
        // test for the bug discovered while implementing ADR-0037 Path B.
        Schema<?> idSchema = new IntegerSchema().format("int64");
        idSchema.setType(null);                      // simulate types-only encoding
        idSchema.setTypes(java.util.Set.of("integer"));
        idSchema.setDefault("");
        Schema<Object> parent = new ObjectSchema();
        parent.setProperties(Map.of("id", idSchema));
        OpenAPI openApi = openApiWithSchema("CustomerDto", parent);

        customizer.customise(openApi);

        assertThat(idSchema.getDefault()).isNull();
    }

    // -- helpers --

    private OpenAPI openApiWithSchema(String name, Schema<?> schema) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, Schema> schemas = new HashMap<>();
        schemas.put(name, schema);
        return new OpenAPI().components(new Components().schemas(schemas));
    }

    private OpenAPI openApiWithParameter(String path, Parameter parameter) {
        Operation op = new Operation().parameters(List.of(parameter));
        PathItem item = new PathItem().get(op);
        Paths paths = new Paths();
        paths.addPathItem(path, item);
        return new OpenAPI().paths(paths);
    }
}
