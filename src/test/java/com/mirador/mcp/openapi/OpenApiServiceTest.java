package com.mirador.mcp.openapi;

import com.mirador.mcp.dto.OpenApiSummary;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OpenApiService}.
 *
 * <p>Builds a small {@link OpenAPI} object hand to keep the tests focused
 * on summarisation logic rather than springdoc machinery.
 */
class OpenApiServiceTest {

    @Test
    void summaryGroupsPathsByVerb() {
        OpenAPI openApi = new OpenAPI();
        openApi.setInfo(new Info().title("test").version("1.0").description("desc"));
        openApi.setPaths(new Paths()
                .addPathItem("/customers", new PathItem()
                        .get(new Operation())
                        .post(new Operation()))
                .addPathItem("/customers/{id}", new PathItem()
                        .get(new Operation())
                        .delete(new Operation())));

        OpenApiService service = new OpenApiService(openApi);
        Object spec = service.getSpec(true);
        assertThat(spec).isInstanceOf(OpenApiSummary.class);
        OpenApiSummary summary = (OpenApiSummary) spec;
        assertThat(summary.info().title()).isEqualTo("test");
        assertThat(summary.info().version()).isEqualTo("1.0");
        assertThat(summary.pathsByVerb())
                .containsEntry("GET", List.of("/customers", "/customers/{id}"))
                .containsEntry("POST", List.of("/customers"))
                .containsEntry("DELETE", List.of("/customers/{id}"));
    }

    @Test
    void summaryWithEmptyPaths() {
        OpenAPI openApi = new OpenAPI();
        openApi.setInfo(new Info().title("empty").version("0"));
        OpenApiService service = new OpenApiService(openApi);

        OpenApiSummary summary = (OpenApiSummary) service.getSpec(true);
        assertThat(summary.pathsByVerb()).isEmpty();
        assertThat(summary.info().title()).isEqualTo("empty");
    }

    @Test
    void summaryHandlesNullInfo() {
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(new Paths().addPathItem("/x", new PathItem().get(new Operation())));
        OpenApiService service = new OpenApiService(openApi);

        OpenApiSummary summary = (OpenApiSummary) service.getSpec(true);
        assertThat(summary.info().title()).isEqualTo("");
        assertThat(summary.info().version()).isEqualTo("");
        assertThat(summary.pathsByVerb()).containsKey("GET");
    }

    @Test
    void fullSpecReturnsTheUnderlyingOpenApiBean() {
        OpenAPI openApi = new OpenAPI();
        OpenApiService service = new OpenApiService(openApi);
        assertThat(service.getSpec(false)).isSameAs(openApi);
    }

    @Test
    void summaryPathsAreSortedAlphabetically() {
        OpenAPI openApi = new OpenAPI();
        openApi.setPaths(new Paths()
                .addPathItem("/zebra", new PathItem().get(new Operation()))
                .addPathItem("/alpha", new PathItem().get(new Operation()))
                .addPathItem("/middle", new PathItem().get(new Operation())));

        OpenApiService service = new OpenApiService(openApi);
        OpenApiSummary summary = (OpenApiSummary) service.getSpec(true);
        assertThat(summary.pathsByVerb().get("GET"))
                .containsExactly("/alpha", "/middle", "/zebra");
    }
}
