package com.mirador.observability.quality.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses OWASP Dependency-Check JSON output into the {@code owasp} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildOwaspSection}
 * under Phase B-1. Includes the CVE-ID + description cleanup helpers
 * ({@link #cleanCveId}, {@link #cleanCveDescription}) that used to be
 * private static members of the endpoint.
 *
 * <h3>Output</h3>
 * <pre>
 * { available, total,
 *   bySeverity: { CRITICAL: N, HIGH: N, MEDIUM: N, LOW: N },
 *   vulnerabilities: [ { cve, severity, score, dependency, description }
 *                       × up to 30, sorted by score desc ] }
 * </pre>
 */
@Component
public class OwaspReportParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CP_OWASP  = "META-INF/build-reports/dependency-check-report.json";
    private static final String DEV_OWASP = "target/dependency-check-report.json";

    private static final String K_AVAILABLE       = "available";
    private static final String K_ERROR           = "error";
    private static final String K_TOTAL           = "total";
    private static final String K_SEVERITY        = "severity";
    private static final String K_SCORE           = "score";
    private static final String K_DESCRIPTION     = "description";
    private static final String K_VULNERABILITIES = "vulnerabilities";
    private static final String K_DEPENDENCIES    = "dependencies";
    private static final String K_UNKNOWN         = "unknown";

    private static final Pattern GHSA_PATTERN = Pattern.compile("(GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4})");

    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_OWASP, DEV_OWASP);
        if (is == null) return Map.of(K_AVAILABLE, false);

        int total = 0;
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        List<Map<String, Object>> vulns = new ArrayList<>();

        try (is) {
            JsonNode root = MAPPER.readTree(is);
            JsonNode dependencies = root.path(K_DEPENDENCIES);
            for (JsonNode dep : dependencies) {
                JsonNode vulnerabilities = dep.path(K_VULNERABILITIES);
                if (vulnerabilities.isEmpty()) continue;

                String depName = dep.path("fileName").asText(K_UNKNOWN);
                for (JsonNode vuln : vulnerabilities) {
                    String rawName  = vuln.path("name").asText("?");
                    String name     = cleanCveId(rawName, vuln.path("references"));
                    String severity = vuln.path(K_SEVERITY).asText("UNKNOWN").toUpperCase();
                    double score    = vuln.path("cvssv3").path("baseScore").asDouble(
                                      vuln.path("cvssv2").path(K_SCORE).asDouble(0.0));
                    String desc     = cleanCveDescription(
                            vuln.path(K_DESCRIPTION).asText(rawName));

                    total++;
                    bySeverity.merge(severity, 1, Integer::sum);

                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("cve",            name);
                    v.put(K_SEVERITY,       severity);
                    v.put(K_SCORE,          score);
                    v.put("dependency",     depName);
                    v.put(K_DESCRIPTION,    desc);
                    vulns.add(v);
                }
            }
            vulns.sort((a, b) -> Double.compare((Double) b.get(K_SCORE), (Double) a.get(K_SCORE)));
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE,        true);
        r.put(K_TOTAL,            total);
        r.put("bySeverity",       bySeverity);
        r.put(K_VULNERABILITIES,  vulns.size() > 30 ? vulns.subList(0, 30) : vulns);
        return r;
    }

    /**
     * Returns a short, displayable identifier for a vulnerability.
     * Proper CVE IDs (CVE-YYYY-NNNNN) are returned as-is; RetireJS/GHSA
     * advisories have full Markdown in the name field, so we pull the
     * GHSA-xxxx ID from references or return a short summary.
     */
    public static String cleanCveId(String rawName, JsonNode references) {
        if (rawName == null || rawName.isBlank()) return "UNKNOWN";
        if (rawName.matches("CVE-\\d{4}-\\d+")) return rawName;
        if (references != null) {
            for (JsonNode ref : references) {
                String url = ref.path("url").asText("");
                Matcher m = GHSA_PATTERN.matcher(url);
                if (m.find()) return m.group(1);
            }
        }
        String first = rawName.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("```"))
                .findFirst().orElse(rawName);
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }

    /**
     * Cleans a CVE description from NVD JSON for display. Some descriptions
     * are full Markdown with HTML code examples (e.g. DOMPurify PoC); we
     * extract only the first meaningful plain-text paragraph.
     */
    // S3776+S135: multi-step text cleaning with interleaved break/continue to skip
    // headers, code fences and empty lines while preserving the first real paragraph —
    // extracting would obscure the flow.
    @SuppressWarnings({"java:S3776", "java:S135"})
    public static String cleanCveDescription(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] lines = raw.split("\n");
        StringBuilder first = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("```") || trimmed.startsWith("- ")) {
                if (!first.isEmpty()) break;
                continue;
            }
            if (trimmed.isEmpty()) {
                if (!first.isEmpty()) break;
                continue;
            }
            if (!first.isEmpty()) first.append(" ");
            first.append(trimmed);
        }
        String result = first.toString()
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                .replaceAll("<[^>]+>", "")
                .trim();
        return result.length() > 200 ? result.substring(0, 200) + "…" : result;
    }
}
