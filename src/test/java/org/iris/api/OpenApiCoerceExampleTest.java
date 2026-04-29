package org.iris.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage for the private {@code coerceExample} method on
 * {@link OpenApiConfig} — exercised through the public OpenApiCustomizer bean.
 *
 * <p>Sibling to {@link OpenApiSchemaSanitizerTest}. Closes the remaining
 * uncovered switch arms (number/boolean/null schemaType + Boolean→string
 * conversion + NumberFormatException catch + object/array fallthrough) and
 * the NullNode branch in {@code dropInvalidDefault}. JaCoCo reported 41 of
 * 104 instructions missed in coerceExample at stable-v1.2.17 ; these tests
 * push class coverage past 95 %.
 */
class OpenApiCoerceExampleTest {

    private final OpenApiCustomizer customizer = new OpenApiConfig().openApiSchemaSanitizer();

    @Test
    void coercesBooleanExampleOnStringSchemaToString() {
        // Real coerceExample branch: example=true (Boolean) but schema.type=string.
        // Boolean#toString -> "true" — same path as Number#toString, separate arm.
        Parameter flagParam = new Parameter()
                .name("flag")
                .in("query")
                .schema(new StringSchema())
                .example(true);
        OpenAPI openApi = openApiWithParameter("/q", flagParam);

        customizer.customise(openApi);

        assertThat(flagParam.getExample()).isNull();
        assertThat(flagParam.getSchema().getExample()).isEqualTo("true");
    }

    @Test
    void coercesParseableStringExampleToNumber() {
        // example="3.14" (String) on a number-typed schema -> Double.parseDouble.
        Parameter priceParam = new Parameter()
                .name("price")
                .in("query")
                .schema(new NumberSchema())
                .example("3.14");
        OpenAPI openApi = openApiWithParameter("/items", priceParam);

        customizer.customise(openApi);

        assertThat(priceParam.getExample()).isNull();
        // NumberSchema casts to BigDecimal — assert numeric value, not raw type.
        Object coerced = priceParam.getSchema().getExample();
        assertThat(coerced).isNotNull();
        assertThat(((Number) coerced).doubleValue()).isEqualTo(3.14);
    }

    @Test
    void coercesParseableStringExampleToBoolean() {
        // example="True" (String) on a boolean-typed schema -> Boolean.parseBoolean
        // path. Case-insensitive match: "true"/"True"/"TRUE" all coerce to true.
        Parameter activeParam = new Parameter()
                .name("active")
                .in("query")
                .schema(new BooleanSchema())
                .example("True");
        OpenAPI openApi = openApiWithParameter("/users", activeParam);

        customizer.customise(openApi);

        assertThat(activeParam.getExample()).isNull();
        assertThat(activeParam.getSchema().getExample()).isEqualTo(true);
    }

    @Test
    void dropsUnparseableStringExampleOnIntegerSchema() {
        // example="abc" (String) on integer schema -> Long.parseLong throws
        // NumberFormatException -> coerceExample returns null -> param.example
        // dropped entirely. Exercises the catch (NumberFormatException) branch
        // that was untested at stable-v1.2.17.
        Parameter idParam = new Parameter()
                .name("id")
                .in("path")
                .schema(new IntegerSchema())
                .example("abc");
        OpenAPI openApi = openApiWithParameter("/customers/{id}", idParam);

        customizer.customise(openApi);

        assertThat(idParam.getExample()).isNull();
        assertThat(idParam.getSchema().getExample()).isNull();
    }

    @Test
    void dropsUnparseableStringExampleOnNumberSchema() {
        // Same NumberFormatException path but for Double.parseDouble — example
        // "not-a-number" on a number schema is dropped rather than emit invalid OAS.
        Parameter param = new Parameter()
                .name("amount")
                .in("query")
                .schema(new NumberSchema())
                .example("not-a-number");
        OpenAPI openApi = openApiWithParameter("/items", param);

        customizer.customise(openApi);

        assertThat(param.getExample()).isNull();
    }

    @Test
    void preservesExampleOnUnknownSchemaType() {
        // schemaType == null branch: when Parameter#schema is null, schemaType
        // resolves to null -> coerceExample returns the example unchanged -> no-op.
        Parameter param = new Parameter()
                .name("x")
                .in("query")
                .example("anything");          // no schema set -> schemaType=null
        OpenAPI openApi = openApiWithParameter("/x", param);

        customizer.customise(openApi);

        // coerced.equals(example) -> leave both alone.
        assertThat(param.getExample()).isEqualTo("anything");
    }

    @Test
    void preservesObjectExampleOnObjectSchema() {
        // schemaType="object" hits the `default -> true` branch in coerceExample's
        // alreadyValid switch — any example is treated as already valid (do not
        // second-guess object/array schemas).
        Map<String, Object> nested = Map.of("k", "v");
        Parameter param = new Parameter()
                .name("body")
                .in("query")
                .schema(new ObjectSchema())
                .example(nested);
        OpenAPI openApi = openApiWithParameter("/o", param);

        customizer.customise(openApi);

        assertThat(param.getExample()).isEqualTo(nested);
    }

    @Test
    void preservesValidNumberExampleOnNumberSchema() {
        // alreadyValid for number: example instanceof Number (Double) -> kept.
        Parameter param = new Parameter()
                .name("amount")
                .in("query")
                .schema(new NumberSchema())
                .example(3.14);
        OpenAPI openApi = openApiWithParameter("/n", param);

        customizer.customise(openApi);

        assertThat(param.getExample()).isEqualTo(3.14);
    }

    @Test
    void preservesValidBooleanExampleOnBooleanSchema() {
        // alreadyValid for boolean: example instanceof Boolean -> kept.
        Parameter param = new Parameter()
                .name("active")
                .in("query")
                .schema(new BooleanSchema())
                .example(false);
        OpenAPI openApi = openApiWithParameter("/b", param);

        customizer.customise(openApi);

        assertThat(param.getExample()).isEqualTo(false);
    }

    @Test
    void treatsJacksonNullNodeDefaultAsNull() {
        // Symmetric to MissingNode (already covered): NullNode.instance also
        // surfaces as `default: null` in JSON output. The class-name endsWith
        // check must catch both — exercises the `.NullNode` branch in
        // dropInvalidDefault that was untested.
        Schema<Object> dto = new ObjectSchema().type("object");
        dto.setDefault(com.fasterxml.jackson.databind.node.NullNode.getInstance());
        OpenAPI openApi = openApiWithSchema("CustomerDto", dto);

        customizer.customise(openApi);

        assertThat(dto.getDefault()).isNull();
    }

    // -- helpers (mirror the style of OpenApiSchemaSanitizerTest) --

    private OpenAPI openApiWithSchema(String name, Schema<?> schema) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        java.util.Map<String, Schema> schemas = new java.util.HashMap<>();
        schemas.put(name, schema);
        return new OpenAPI().components(
                new io.swagger.v3.oas.models.Components().schemas(schemas));
    }

    private OpenAPI openApiWithParameter(String path, Parameter parameter) {
        Operation op = new Operation().parameters(List.of(parameter));
        PathItem item = new PathItem().get(op);
        Paths paths = new Paths();
        paths.addPathItem(path, item);
        return new OpenAPI().paths(paths);
    }
}
