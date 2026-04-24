package com.mirador.observability.quality.providers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirador.observability.quality.parsers.ReportParsers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses {@code pom.xml} + build-time dependency reports into the
 * {@code dependencies} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildDependenciesSection}
 * under Phase B-1b, staging for Phase Q-2. The 4-step assembly:
 *
 * <ol>
 *   <li><b>pom.xml</b> — XML parse, extract direct dependencies + property
 *       references (resolves {@code ${spring.boot.version}} etc.).</li>
 *   <li><b>Maven Central</b> — optional live freshness check
 *       (search.maven.org). Adds {@code latestVersion} + {@code outdated}
 *       fields per dep. Capped at 25 parallel requests, 8 s total timeout
 *       so the endpoint never blocks. This call lives on a
 *       build-time boundary (per ADR-0052 / Phase Q-2, the provider will
 *       eventually run at {@code mvn prepare-package} only, making the
 *       Maven Central call a legitimate build-time concern).</li>
 *   <li><b>dependency-tree.txt</b> — raw output of
 *       {@code maven-dependency-plugin:tree}, pre-generated at build time.
 *       Used for the transitive-count display in the UI.</li>
 *   <li><b>dependency-analysis.txt</b> — output of
 *       {@code maven-dependency-plugin:analyze-only} — flags
 *       used-undeclared + unused-declared dependencies.</li>
 * </ol>
 */
@Component
public class DependenciesSectionProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CP_POM          = "META-INF/build-reports/pom.xml";
    private static final String DEV_POM         = "pom.xml";
    private static final String CP_DEP_TREE     = "META-INF/build-reports/dependency-tree.txt";
    private static final String DEV_DEP_TREE    = "target/dependency-tree.txt";
    private static final String CP_DEP_ANALYZE  = "META-INF/build-reports/dependency-analysis.txt";
    private static final String DEV_DEP_ANALYZE = "target/dependency-analysis.txt";

    private static final String K_AVAILABLE    = "available";
    private static final String K_ERROR        = "error";
    private static final String K_TOTAL        = "total";
    private static final String K_VERSION      = "version";
    private static final String K_DEPENDENCIES = "dependencies";
    private static final String K_GROUP_ID     = "groupId";
    private static final String K_ARTIFACT_ID  = "artifactId";

    /** Package prefixes that indicate a dependency coordinate line (vs comments/headers). */
    private static final List<String> COORD_PREFIXES = List.of(
            "com.", "org.", "io.", "net.", "jakarta.", "javax.", "ch.", "de.");

    // S3776 + S135 + S6541: intentional accumulation across 4 build-tool outputs; extracting
    // would spread the data across several helpers without improving clarity.
    @SuppressWarnings({"java:S3776", "java:S135", "java:S6541"})
    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_POM, DEV_POM);
        if (is == null) return Map.of(K_AVAILABLE, false);

        // Step 1 — pom.xml: properties + direct dependencies
        Map<String, String> pomProperties = new HashMap<>();
        List<Map<String, Object>> deps = new ArrayList<>();
        try (is) {
            DocumentBuilder db = ReportParsers.secureNamespaceAwareDocumentBuilder();
            Document doc = db.parse(is);
            collectProperties(doc, pomProperties);
            collectDependencies(doc, pomProperties, deps);
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        // Step 2 — Maven Central freshness check (capped + timeboxed)
        long outdatedCount = fetchLatestVersions(deps);

        // Step 3 — dependency tree
        Map<String, Object> treeResult = loadDependencyTree();

        // Step 4 — dependency analysis (used-undeclared / unused-declared)
        Map<String, Object> analyzeResult = parseDependencyAnalysis();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE,   true);
        r.put(K_TOTAL,       deps.size());
        r.put("outdatedCount", outdatedCount);
        if (treeResult != null)    r.put("dependencyTree",    treeResult);
        if (analyzeResult != null) r.put("dependencyAnalysis", analyzeResult);
        r.put(K_DEPENDENCIES, deps);
        return r;
    }

    // ── pom.xml helpers ─────────────────────────────────────────────────

    private static void collectProperties(Document doc, Map<String, String> out) {
        NodeList propNodes = doc.getElementsByTagName("properties");
        for (int i = 0; i < propNodes.getLength(); i++) {
            Element propsEl = (Element) propNodes.item(i);
            if (!"project".equals(propsEl.getParentNode().getNodeName())) continue;
            NodeList children = propsEl.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (children.item(j) instanceof Element propEl) {
                    out.put(propEl.getTagName(), propEl.getTextContent().trim());
                }
            }
        }
    }

    private static void collectDependencies(Document doc, Map<String, String> props, List<Map<String, Object>> out) {
        NodeList depNodes = doc.getElementsByTagName("dependency");
        for (int i = 0; i < depNodes.getLength(); i++) {
            Element dep = (Element) depNodes.item(i);
            if (!K_DEPENDENCIES.equals(dep.getParentNode().getNodeName())) continue;
            String groupId    = ReportParsers.getTagText(dep, K_GROUP_ID);
            String artifactId = ReportParsers.getTagText(dep, K_ARTIFACT_ID);
            String rawVersion = ReportParsers.getTagText(dep, K_VERSION);
            String scope      = ReportParsers.getTagText(dep, "scope");
            if (scope.isEmpty()) scope = "compile";

            String resolvedVersion = rawVersion;
            if (rawVersion.startsWith("${") && rawVersion.endsWith("}")) {
                String key = rawVersion.substring(2, rawVersion.length() - 1);
                resolvedVersion = props.getOrDefault(key, rawVersion);
            }

            Map<String, Object> d = new LinkedHashMap<>();
            d.put(K_GROUP_ID,    groupId);
            d.put(K_ARTIFACT_ID, artifactId);
            d.put(K_VERSION,     resolvedVersion.isEmpty() ? "(managed)" : resolvedVersion);
            d.put("scope",       scope);
            out.add(d);
        }
    }

    // ── Maven Central freshness ─────────────────────────────────────────

    private long fetchLatestVersions(List<Map<String, Object>> deps) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map<String, Object> dep : deps) {
            String version = (String) dep.get(K_VERSION);
            if (version == null || version.startsWith("(") || version.startsWith("${")) continue;
            if (futures.size() >= 25) break;
            futures.add(CompletableFuture.runAsync(() -> enrichWithLatest(client, dep, version)));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // timeout / interruption — return whatever was collected
        }
        return deps.stream().filter(d -> Boolean.TRUE.equals(d.get("outdated"))).count();
    }

    private static void enrichWithLatest(HttpClient client, Map<String, Object> dep, String version) {
        String g = (String) dep.get(K_GROUP_ID);
        String a = (String) dep.get(K_ARTIFACT_ID);
        String url = "https://search.maven.org/solrsearch/select?rows=1&wt=json&q="
                + URLEncoder.encode("g:" + g + " AND a:" + a, StandardCharsets.UTF_8);
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return;
            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode docs = root.path("response").path("docs");
            if (!docs.isArray() || docs.isEmpty()) return;
            String latest = docs.get(0).path("latestVersion").asText("");
            if (latest.isBlank()) return;
            dep.put("latestVersion", latest);
            dep.put("outdated", isOutdated(version, latest));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
            // non-critical; dep appears without latestVersion
        }
    }

    private static boolean isOutdated(String current, String latest) {
        return !latest.equals(current)
                && !current.contains("SNAPSHOT")
                && !current.contains("-M")
                && !current.contains("-RC")
                && !current.contains("-alpha")
                && !current.contains("-beta");
    }

    // ── Dependency tree ─────────────────────────────────────────────────

    private static Map<String, Object> loadDependencyTree() {
        InputStream is = ReportParsers.loadResource(CP_DEP_TREE, DEV_DEP_TREE);
        if (is == null) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = br.readLine()) != null) lines.add(line);
            String rawTree = String.join("\n", lines);
            long transitiveCount = lines.stream()
                    .filter(l -> l.startsWith("   ") || l.startsWith("\\-") || l.startsWith("+"))
                    .count();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(K_AVAILABLE, true);
            result.put("tree", rawTree);
            result.put("totalTransitive", transitiveCount);
            return result;
        } catch (IOException ignored) {
            return null;
        }
    }

    // ── Dependency analysis ─────────────────────────────────────────────

    // S1168: null (rather than an empty map) signals "section absent" so the caller can omit the
    // `dependencyAnalysis` key entirely — distinguishes "not generated" from "analyzed, no findings".
    @SuppressWarnings({"java:S3776", "java:S1168"})
    private static Map<String, Object> parseDependencyAnalysis() {
        InputStream is = ReportParsers.loadResource(CP_DEP_ANALYZE, DEV_DEP_ANALYZE);
        if (is == null) return null;
        List<String> usedUndeclared = new ArrayList<>();
        List<String> unusedDeclared = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String section = null;
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.contains("Used undeclared"))       section = "used";
                else if (trimmed.contains("Unused declared"))  section = "unused";
                else if (isCoordLine(trimmed)) {
                    if ("used".equals(section))   usedUndeclared.add(trimmed);
                    if ("unused".equals(section)) unusedDeclared.add(trimmed);
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE,                  true);
        result.put("usedUndeclared",             usedUndeclared);
        result.put("usedUndeclaredCount",        usedUndeclared.size());
        result.put("unusedDeclared",             unusedDeclared);
        result.put("unusedDeclaredCount",        unusedDeclared.size());
        return result;
    }

    private static boolean isCoordLine(String line) {
        return COORD_PREFIXES.stream().anyMatch(line::startsWith);
    }
}
