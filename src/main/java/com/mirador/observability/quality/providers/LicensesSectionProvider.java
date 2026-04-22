package com.mirador.observability.quality.providers;

import com.mirador.observability.quality.parsers.ReportParsers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Parses {@code target/classes/META-INF/build-reports/THIRD-PARTY.txt}
 * (produced by {@code license-maven-plugin:add-third-party}) into the
 * {@code licenses} section of the quality report.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildLicensesSection}
 * under Phase B-1b, staging for Phase Q-2 (move to build-time JSON per
 * ADR-0052).
 *
 * <h3>Input format</h3>
 * Each non-header line: {@code (License Name) group:artifact:version - Display Name}.
 * Lines not starting with {@code (} are treated as header / separator.
 *
 * <h3>Output</h3>
 * <pre>
 * { available: true,
 *   total: N,
 *   incompatibleCount: N,       // GPL/AGPL/LGPL/CDDL/EPL count
 *   licenses: [ { license, count, incompatible } … sorted by count desc ],
 *   dependencies: [ { group, artifact, version, license, incompatible } … ] }
 * </pre>
 */
@Component
public class LicensesSectionProvider {

    private static final String CP_THIRD_PARTY = "META-INF/build-reports/THIRD-PARTY.txt";
    private static final String DEV_THIRD_PARTY = "target/THIRD-PARTY.txt";

    private static final String K_AVAILABLE    = "available";
    private static final String K_ERROR        = "error";
    private static final String K_TOTAL        = "total";
    private static final String K_VERSION      = "version";
    private static final String K_DEPENDENCIES = "dependencies";
    private static final String K_COUNT        = "count";

    /** License keywords that indicate potential incompatibility with proprietary/commercial use. */
    private static final List<String> RESTRICTED = List.of("GPL", "AGPL", "LGPL", "CDDL", "EPL");

    // S3776 + S135: multiple early-skip branches for header + malformed rows — same
    // rationale as the original inline method; restructuring hides validation cliffs.
    @SuppressWarnings({"java:S3776", "java:S135"})
    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_THIRD_PARTY, DEV_THIRD_PARTY);
        if (is == null) return Map.of(K_AVAILABLE, false);

        List<Map<String, Object>> deps = new ArrayList<>();
        Map<String, Integer> licenseCounts = new LinkedHashMap<>();
        int incompatibleCount = 0;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.startsWith("(")) continue;
                int closeIdx = trimmed.indexOf(')');
                if (closeIdx < 0) continue;
                String licenseStr = trimmed.substring(1, closeIdx).trim();
                String rest = trimmed.substring(closeIdx + 1).trim();
                String coords = rest.contains(" - ") ? rest.substring(0, rest.indexOf(" - ")).trim() : rest.trim();
                String[] parts = coords.split(":");
                if (parts.length < 2) continue;
                String groupId    = parts[0];
                String artifactId = parts[1];
                String version    = parts.length >= 3 ? parts[2] : "";
                boolean incompatible = isRestricted(licenseStr);
                if (incompatible) incompatibleCount++;
                licenseCounts.merge(licenseStr, 1, Integer::sum);

                Map<String, Object> dep = new LinkedHashMap<>();
                dep.put("group",        groupId);
                dep.put("artifact",     artifactId);
                dep.put(K_VERSION,      version);
                dep.put("license",      licenseStr);
                dep.put("incompatible", incompatible);
                deps.add(dep);
            }
        } catch (IOException e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        List<Map<String, Object>> licenseSummary = licenseCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> Map.<String, Object>of(
                        "license",      e.getKey(),
                        K_COUNT,        e.getValue(),
                        "incompatible", isRestricted(e.getKey())))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE,       true);
        result.put(K_TOTAL,           deps.size());
        result.put("incompatibleCount", incompatibleCount);
        result.put("licenses",        licenseSummary);
        result.put(K_DEPENDENCIES,    deps);
        return result;
    }

    private static boolean isRestricted(String license) {
        String upper = license.toUpperCase();
        return RESTRICTED.stream().anyMatch(upper::contains);
    }
}
