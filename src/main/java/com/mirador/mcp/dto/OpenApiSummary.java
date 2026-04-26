package com.mirador.mcp.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Compact OpenAPI summary surfaced by {@code get_openapi_spec(summary=true)}.
 *
 * <p>The full OpenAPI 3.1 document includes per-endpoint schemas, examples,
 * security definitions, and component models — easily 200 KB. For an LLM
 * trying to find "which endpoint accepts a customer creation payload",
 * the long version is noise. The summary keeps {@link InfoBlock} (title,
 * version, description) plus {@link #pathsByVerb()} : a flat map of
 * HTTP method → list of paths.
 *
 * @param info         /openapi info block
 * @param pathsByVerb  map of HTTP verb (uppercase) to list of paths
 */
@Schema(description = "Compact OpenAPI summary — info block + paths grouped by verb")
public record OpenApiSummary(
        InfoBlock info,
        Map<String, List<String>> pathsByVerb
) {

    /**
     * Subset of the OpenAPI {@code info} block kept in the summary.
     *
     * @param title       service title (e.g. {@code mirador})
     * @param version     service version (e.g. {@code 0.0.1-SNAPSHOT})
     * @param description short description, may be {@code null}
     */
    @Schema(description = "OpenAPI info block (subset)")
    public record InfoBlock(
            String title,
            String version,
            String description
    ) {
    }
}
