package com.mirador.mcp.openapi;

import com.mirador.mcp.dto.OpenApiSummary;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * MCP tool surface for the in-process OpenAPI specification.
 *
 * <p>Delegates to the in-process {@link OpenAPI} bean already auto-wired
 * by the springdoc-openapi starter — no extra Maven dependency,
 * no HTTP self-call. The existing {@code /v3/api-docs} endpoint serves the
 * same content over HTTP for human consumers ; this service surfaces it
 * to LLM tools while staying inside the JVM.
 *
 * <h3>Two response shapes</h3>
 * <ul>
 *   <li>{@code summary=true} → compact {@link OpenApiSummary} (info block +
 *       paths grouped by HTTP verb). ~1 KB. Use this when you want the LLM
 *       to "find which endpoint accepts a payload" without flooding its
 *       window.</li>
 *   <li>{@code summary=false} → full OpenAPI 3.1 document (~200 KB).
 *       Use only when you need schemas + examples for code generation.</li>
 * </ul>
 *
 * <p>The full document is returned as a {@link Map} (not the raw
 * {@link OpenAPI} POJO) so the MCP serialiser produces a stable JSON shape
 * unaffected by Jackson view annotations on the Swagger model classes.
 */
@Service
public class OpenApiService {

    private final OpenAPI openApi;

    /**
     * @param openApi the in-process OpenAPI bean — Spring auto-wires it
     *                through the springdoc starter, computed lazily but
     *                available by the time MCP tools fire
     */
    public OpenApiService(OpenAPI openApi) {
        this.openApi = openApi;
    }

    /**
     * Returns the API specification ; {@code summary=true} keeps the
     * payload tiny by stripping schemas and component definitions.
     *
     * @param summary when true, return only the info block + paths-by-verb ;
     *                otherwise return the full document
     * @return either a typed {@link OpenApiSummary} record or a {@link Map}
     *         carrying the full spec — the MCP framework serialises both
     *         to JSON identically
     */
    @Tool(name = "get_openapi_spec",
            description = "Returns the in-process OpenAPI 3.1 specification. summary=true "
                    + "produces a compact list (info block + paths grouped by HTTP verb, "
                    + "~1 KB) — use this to find which endpoint accepts a payload. "
                    + "summary=false returns the full spec including schemas + examples.")
    public Object getSpec(
            @ToolParam(description = "true → compact paths-by-verb summary ; false → full spec.")
            boolean summary
    ) {
        if (summary) {
            return buildSummary();
        }
        return openApi;
    }

    /**
     * Walks the OpenAPI {@code paths} map and groups path strings by
     * uppercase HTTP verb. Returns a {@link TreeMap} so verbs come back in
     * a stable lexical order (DELETE / GET / HEAD / OPTIONS / PATCH / POST /
     * PUT / TRACE).
     */
    OpenApiSummary buildSummary() {
        Map<String, List<String>> byVerb = new TreeMap<>();
        if (openApi.getPaths() != null) {
            for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
                String path = entry.getKey();
                PathItem item = entry.getValue();
                for (Map.Entry<PathItem.HttpMethod, Operation> op : item.readOperationsMap().entrySet()) {
                    String verb = op.getKey().name().toUpperCase(Locale.ROOT);
                    byVerb.computeIfAbsent(verb, k -> new ArrayList<>()).add(path);
                }
            }
        }
        // Sort each path list alphabetically — stable output across runs.
        Map<String, List<String>> sortedByVerb = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : byVerb.entrySet()) {
            List<String> sortedPaths = e.getValue().stream().sorted().toList();
            sortedByVerb.put(e.getKey(), sortedPaths);
        }
        return new OpenApiSummary(buildInfoBlock(), sortedByVerb);
    }

    /** Builds the info block subset retained in summaries. */
    private OpenApiSummary.InfoBlock buildInfoBlock() {
        Info info = openApi.getInfo();
        if (info == null) {
            return new OpenApiSummary.InfoBlock("", "", null);
        }
        return new OpenApiSummary.InfoBlock(
                info.getTitle() == null ? "" : info.getTitle(),
                info.getVersion() == null ? "" : info.getVersion(),
                info.getDescription()
        );
    }
}
