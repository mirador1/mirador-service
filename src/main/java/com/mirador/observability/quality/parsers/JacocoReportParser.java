package com.mirador.observability.quality.parsers;

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
 * Parses JaCoCo CSV output into the {@code coverage} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildCoverageSection}
 * under Phase B-1.
 *
 * <h3>Data sources</h3>
 * <ol>
 *   <li>Classpath {@code META-INF/build-reports/jacoco.csv} (packaged).</li>
 *   <li>Filesystem: prefer {@code target/site/jacoco-merged/jacoco.csv}
 *       (unit + IT merged) — same data the {@code jacoco:check} gate reads;
 *       falls back to {@code target/site/jacoco/jacoco.csv} when the IT
 *       suite was skipped ({@code mvn verify -DskipITs}).</li>
 * </ol>
 *
 * <h3>Output</h3>
 * <pre>
 * { available: true,
 *   instructions: { covered, total, pct },
 *   branches, lines, methods (same shape),
 *   packages: [ { name, instructionPct, linePct } … ] }
 * </pre>
 *
 * <h3>CSV columns (JaCoCo 0.8.x)</h3>
 * GROUP, PACKAGE, CLASS, INSTRUCTION_MISSED, INSTRUCTION_COVERED,
 * BRANCH_MISSED, BRANCH_COVERED, LINE_MISSED, LINE_COVERED,
 * COMPLEXITY_MISSED, COMPLEXITY_COVERED, METHOD_MISSED, METHOD_COVERED.
 */
@Component
public class JacocoReportParser {

    private static final String CP_JACOCO       = "META-INF/build-reports/jacoco.csv";
    private static final String DEV_JACOCO      = "target/site/jacoco-merged/jacoco.csv";
    private static final String DEV_JACOCO_UNIT = "target/site/jacoco/jacoco.csv";

    private static final String K_AVAILABLE = "available";
    private static final String K_ERROR     = "error";
    private static final String K_TOTAL     = "total";
    private static final String K_BRANCHES  = "branches";
    private static final String K_METHODS   = "methods";

    // S1141 + S135 + S3776: the inner try/catch + several `continue`s is idiomatic
    // for "skip malformed CSV row, aggregate the rest" — restructuring to one exit
    // point would hide the data-validation cliff.
    @SuppressWarnings({"java:S3776", "java:S1141", "java:S135"})
    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_JACOCO, DEV_JACOCO);
        if (is == null) {
            is = ReportParsers.loadResource(CP_JACOCO, DEV_JACOCO_UNIT);
        }
        if (is == null) {
            return Map.of(K_AVAILABLE, false);
        }

        long instrCovered = 0;
        long instrTotal = 0;
        long branchCovered = 0;
        long branchTotal = 0;
        long lineCovered = 0;
        long lineTotal = 0;
        long methodCovered = 0;
        long methodTotal = 0;
        // Map<packageName, [lineCovered, lineTotal, instrCovered, instrTotal]>
        Map<String, long[]> pkgData = new LinkedHashMap<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] cols = line.split(",", -1);
                if (cols.length < 13) continue;
                try {
                    long iMissed = Long.parseLong(cols[3].trim());
                    long iCovered = Long.parseLong(cols[4].trim());
                    long bMissed = Long.parseLong(cols[5].trim());
                    long bCovered = Long.parseLong(cols[6].trim());
                    long lMissed = Long.parseLong(cols[7].trim());
                    long lCovered = Long.parseLong(cols[8].trim());
                    long mMissed = Long.parseLong(cols[11].trim());
                    long mCovered = Long.parseLong(cols[12].trim());

                    instrCovered += iCovered;
                    instrTotal += iMissed + iCovered;
                    branchCovered += bCovered;
                    branchTotal += bMissed + bCovered;
                    lineCovered += lCovered;
                    lineTotal += lMissed + lCovered;
                    methodCovered += mCovered;
                    methodTotal += mMissed + mCovered;

                    String pkg = cols[1].trim();
                    String pkgShort = pkg.isEmpty() ? "(default)" : pkg.replace('/', '.');
                    String[] pkgParts = pkgShort.split("\\.");
                    String pkgDisplay = pkgParts[pkgParts.length - 1];

                    pkgData.merge(pkgDisplay,
                            new long[]{lCovered, lMissed + lCovered, iCovered, iMissed + iCovered},
                            (a, b) -> new long[]{a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]});
                } catch (NumberFormatException ignored) {
                    // skip malformed lines
                }
            }
        } catch (IOException e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        List<Map<String, Object>> packages = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : pkgData.entrySet()) {
            long[] d = entry.getValue();
            double instrPct = d[3] > 0 ? ReportParsers.round1(100.0 * d[2] / d[3]) : 0.0;
            double linePct  = d[1] > 0 ? ReportParsers.round1(100.0 * d[0] / d[1]) : 0.0;
            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("name", entry.getKey());
            pkg.put("instructionPct", instrPct);
            pkg.put("linePct", linePct);
            packages.add(pkg);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE, true);
        result.put("instructions", counterMap(instrCovered, instrTotal));
        result.put(K_BRANCHES, counterMap(branchCovered, branchTotal));
        result.put("lines", counterMap(lineCovered, lineTotal));
        result.put(K_METHODS, counterMap(methodCovered, methodTotal));
        result.put("packages", packages);
        return result;
    }

    private static Map<String, Object> counterMap(long covered, long total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("covered", covered);
        m.put(K_TOTAL, total);
        m.put("pct", total > 0 ? ReportParsers.round1(100.0 * covered / total) : 0.0);
        return m;
    }
}
