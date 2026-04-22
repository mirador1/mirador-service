package com.mirador.observability.quality.providers;

import com.mirador.observability.quality.parsers.ReportParsers;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Reads JaCoCo CSV to compute cyclomatic-complexity metrics per package +
 * top-10 most complex classes + classes with 0 % method coverage.
 *
 * <p>Extracted 2026-04-22 from
 * {@code QualityReportEndpoint.buildMetricsSection} under Phase Q-2b
 * (ADR-0052 completion — was the last runtime JaCoCo CSV read).
 *
 * <h3>Output</h3>
 * <pre>
 * { available, totalClasses, totalMethods, totalLines, totalComplexity,
 *   packages: [ { name, classes, lines, methods, complexity } …
 *              sorted by complexity desc ],
 *   topComplexClasses: [ { class, complexity } × up to 10 ],
 *   untestedClasses: [ "ClassName" … ] (sorted alphabetically, deduplicated),
 *   untestedCount }
 * </pre>
 *
 * <h3>JaCoCo CSV columns (0-indexed)</h3>
 * GROUP(0), PACKAGE(1), CLASS(2), INSTRUCTION_MISSED(3), INSTRUCTION_COVERED(4),
 * BRANCH_MISSED(5), BRANCH_COVERED(6), LINE_MISSED(7), LINE_COVERED(8),
 * COMPLEXITY_MISSED(9), COMPLEXITY_COVERED(10), METHOD_MISSED(11), METHOD_COVERED(12).
 *
 * <h3>Data source priority</h3>
 * <ol>
 *   <li>Classpath: {@code META-INF/build-reports/jacoco.csv}.</li>
 *   <li>Filesystem: {@code target/site/jacoco-merged/jacoco.csv}
 *       (merged unit + IT — same data as the coverage gate reads).</li>
 *   <li>Fallback: {@code target/site/jacoco/jacoco.csv} (unit-only).</li>
 * </ol>
 */
@Component
public class MetricsSectionProvider {

    private static final String CP_JACOCO       = "META-INF/build-reports/jacoco.csv";
    private static final String DEV_JACOCO      = "target/site/jacoco-merged/jacoco.csv";
    private static final String DEV_JACOCO_UNIT = "target/site/jacoco/jacoco.csv";

    private static final String K_AVAILABLE  = "available";
    private static final String K_ERROR      = "error";
    private static final String K_METHODS    = "methods";
    private static final String K_COMPLEXITY = "complexity";

    // S1141 + S135 + S3776: intentional skip-malformed-row pattern + multiple
    // accumulators across classes/methods/lines/complexity in one pass.
    @SuppressWarnings({"java:S1141", "java:S135", "java:S3776"})
    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_JACOCO, DEV_JACOCO);
        if (is == null) is = ReportParsers.loadResource(CP_JACOCO, DEV_JACOCO_UNIT);
        if (is == null) return Map.of(K_AVAILABLE, false);

        long totalClasses = 0;
        long totalMethods = 0;
        long totalLines = 0;
        long totalComplexity = 0;
        // Map<packageShortName, [classes, lines, methods, complexity]>
        Map<String, long[]> pkgMetrics = new LinkedHashMap<>();
        // Class-level complexity for top-10 view: [complexity, nameIndex]
        List<long[]> classComplexity = new ArrayList<>();
        List<String> classNames = new ArrayList<>();
        // Classes with 0 % method coverage (METHOD_COVERED=0 && METHOD_TOTAL>0)
        List<String> untestedClasses = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] cols = line.split(",", -1);
                if (cols.length < 13) continue;
                try {
                    long lineMissed    = Long.parseLong(cols[7].trim());
                    long lineCovered   = Long.parseLong(cols[8].trim());
                    long cxMissed      = Long.parseLong(cols[9].trim());
                    long cxCovered     = Long.parseLong(cols[10].trim());
                    long methodMissed  = Long.parseLong(cols[11].trim());
                    long methodCovered = Long.parseLong(cols[12].trim());
                    long lines      = lineMissed + lineCovered;
                    long methods    = methodMissed + methodCovered;
                    long complexity = cxMissed + cxCovered;

                    totalClasses++;
                    totalMethods    += methods;
                    totalLines      += lines;
                    totalComplexity += complexity;

                    String pkg = cols[1].trim();
                    String[] parts = pkg.replace('/', '.').split("\\.");
                    String pkgShort = parts[parts.length - 1];
                    pkgMetrics.merge(pkgShort, new long[]{1, lines, methods, complexity},
                            (a, b) -> new long[]{a[0] + 1, a[1] + b[1], a[2] + b[2], a[3] + b[3]});

                    String rawClass    = cols[2].trim();
                    String simpleClass = rawClass.contains("$")
                            ? rawClass.substring(0, rawClass.indexOf('$'))
                            : rawClass;
                    int idx = classNames.size();
                    classNames.add(simpleClass);
                    classComplexity.add(new long[]{complexity, idx});

                    // Excludes pure data classes (records, DTOs, 0 methods) — no logic to test.
                    if (methodCovered == 0 && methods > 0) {
                        untestedClasses.add(simpleClass);
                    }
                } catch (NumberFormatException _) {
                    // JaCoCo occasionally leaks non-numeric totals on synthetic classes — skip row.
                }
            }
        } catch (IOException e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        // Package aggregate, sorted by complexity desc.
        List<Map<String, Object>> packages = new ArrayList<>();
        for (Map.Entry<String, long[]> e : pkgMetrics.entrySet()) {
            long[] v = e.getValue();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("name",       e.getKey());
            p.put("classes",    v[0]);
            p.put("lines",      v[1]);
            p.put(K_METHODS,    v[2]);
            p.put(K_COMPLEXITY, v[3]);
            packages.add(p);
        }
        packages.sort((a, b) -> Long.compare((Long) b.get(K_COMPLEXITY), (Long) a.get(K_COMPLEXITY)));

        // Top-10 most complex classes (dedup by simple name — inner classes merged).
        classComplexity.sort((a, b) -> Long.compare(b[0], a[0]));
        List<Map<String, Object>> topComplex = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (long[] cc : classComplexity) {
            String name = classNames.get((int) cc[1]);
            if (seen.add(name) && topComplex.size() < 10) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("class",        name);
                entry.put(K_COMPLEXITY,   cc[0]);
                topComplex.add(entry);
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE,        true);
        r.put("totalClasses",     totalClasses);
        r.put("totalMethods",     totalMethods);
        r.put("totalLines",       totalLines);
        r.put("totalComplexity",  totalComplexity);
        r.put("packages",         packages);
        r.put("topComplexClasses", topComplex);
        Set<String> untestedSet = new TreeSet<>(untestedClasses);
        r.put("untestedClasses",  new ArrayList<>(untestedSet));
        r.put("untestedCount",    untestedSet.size());
        return r;
    }
}
