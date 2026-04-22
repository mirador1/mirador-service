package com.mirador.observability.quality.providers;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests for Phase B-1b providers. Each test hits the provider's
 * {@code parse()} entry point and locks the "returns a Map with an
 * {@code available} flag" contract. Richer fixture-based tests are
 * tracked as part of the B-1b coverage follow-up in TASKS.md.
 */
class QualityProvidersSmokeTest {

    @Test
    void buildInfo_parse_returnsMapWithAvailableFlag() {
        Map<String, Object> r = new BuildInfoSectionProvider().parse();
        assertThat(r).isNotNull().containsKey("available");
    }

    @Test
    void api_parse_withEmptyHandlerMapping_returnsAvailableTrue() {
        RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
        Map<String, Object> r = new ApiSectionProvider(mapping).parse();
        assertThat(r).isNotNull().containsKey("available");
        // With no handlers registered, total = 0 and endpoints list is empty.
        assertThat(r.get("total")).isEqualTo(0);
    }
}
