package com.mirador.observability.quality.parsers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilder;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses surefire + failsafe {@code TEST-*.xml} reports into the
 * {@code tests} section of the quality endpoint.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildTestsSection}
 * under Phase B-1. Contains what used to be three intertwined methods
 * ({@code buildTestsSection} + {@code loadSurefireStreams} + {@code parseOneSuite}
 * + the {@code ParsedSuite} record) — kept together here because they
 * only make sense as one pipeline, and the endpoint now calls a single
 * {@code parse()} entry point.
 *
 * <h3>Data sources in priority order</h3>
 * <ol>
 *   <li><b>Classpath</b>: {@code META-INF/build-reports/surefire/TEST-*.xml}
 *       — present when the JAR was built with the Antrun-copy step. This
 *       is what production Actuator calls see.</li>
 *   <li><b>Filesystem fallback</b>: {@code target/surefire-reports/TEST-*.xml}
 *       — used during local {@code mvn verify} without packaging.</li>
 * </ol>
 *
 * <h3>Output shape</h3>
 * <pre>
 * { available: true,
 *   status: "PASSED" | "FAILED",
 *   total, passed, failures, errors, skipped,
 *   time: "1.23s",
 *   runAt: "2026-04-22 10:50:00",
 *   suites: [ { name, tests, failures, errors, skipped, time } … ],
 *   slowestTests: [ { name, time, timeMs } × up to 10 ] }
 * </pre>
 * Returns {@code {available: false}} when no TEST-*.xml found,
 * {@code {available: false, error: ...}} if the XML parser can't be
 * built (misconfigured JVM, rare).
 *
 * <h3>Dedup strategy</h3>
 * Suites are keyed by simple class name, not fully qualified — after a
 * package rename without {@code mvn clean} both the old and new
 * TEST-*.xml coexist in {@code target/} and would double-count the
 * totals. Dedup by short name picks whichever the directory iterator
 * visits first; re-running {@code mvn clean verify} yields a stable
 * report.
 */
@Component
public class SurefireReportParser {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CP_SUREFIRE_PATTERN = "META-INF/build-reports/surefire/TEST-*.xml";
    private static final String DEV_SUREFIRE_DIR    = "target/surefire-reports";

    // Map keys — duplicated here rather than imported from the endpoint so
    // this parser carries no upward dependency. Same values (S1192 OK — each
    // parser owns its own vocabulary for the section it produces).
    private static final String K_AVAILABLE = "available";
    private static final String K_ERROR     = "error";
    private static final String K_STATUS    = "status";
    private static final String K_TOTAL     = "total";
    private static final String K_TESTS     = "tests";
    private static final String K_FAILURES  = "failures";
    private static final String K_ERRORS    = "errors";
    private static final String K_SKIPPED   = "skipped";
    private static final String K_TIME_MS   = "timeMs";

    /**
     * Parses every discoverable surefire/failsafe suite and builds the
     * {@code tests} section map. See class-level Javadoc for output shape
     * and {@code {available: false}} fallback semantics.
     */
    // S3776: cognitive complexity is intentionally above 15 here — the parse
    // loop accumulates across 7 counters + 3 lists in one pass. Extracting
    // sub-methods would either force the accumulators into class fields
    // (stateful, not thread-safe) or a larger result record (noise).
    @SuppressWarnings("java:S3776")
    public Map<String, Object> parse() {
        List<InputStream> streams = loadSurefireStreams();
        if (streams.isEmpty()) {
            return Map.of(K_AVAILABLE, false);
        }

        int totalTests = 0;
        int totalFailures = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0.0;
        long lastModified = System.currentTimeMillis();
        List<Map<String, Object>> suites = new ArrayList<>();
        List<double[]> allTestCases = new ArrayList<>();
        List<String> allTestCaseNames = new ArrayList<>();
        // Dedup key = simple class name. Stale reports from pre-rename packages
        // (no mvn clean between moves) would otherwise double-count.
        Set<String> seenShortNames = new LinkedHashSet<>();

        DocumentBuilder docBuilder;
        try {
            docBuilder = ReportParsers.secureDocumentBuilder();
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }
        for (InputStream is : streams) {
            ParsedSuite p = parseOneSuite(is, docBuilder);
            if (p == null || !seenShortNames.add(p.shortName())) continue;
            totalTests    += p.tests();
            totalFailures += p.failures();
            totalErrors   += p.errors();
            totalSkipped  += p.skipped();
            totalTime     += p.time();
            suites.add(p.display());
            allTestCases.addAll(p.testCaseTimes());
            allTestCaseNames.addAll(p.testCaseNames());
        }

        String runAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(lastModified), ZoneId.systemDefault()).format(TS_FMT);
        boolean allPassed = totalFailures == 0 && totalErrors == 0;

        // Slowest 10 tests — useful for dashboard "what's dragging the suite".
        List<Map<String, Object>> slowestTests = new ArrayList<>();
        for (int i = 0; i < allTestCaseNames.size(); i++) {
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("name", allTestCaseNames.get(i));
            tc.put("time", String.format("%.3fs", allTestCases.get(i)[0]));
            tc.put(K_TIME_MS, (long)(allTestCases.get(i)[0] * 1000));
            slowestTests.add(tc);
        }
        slowestTests.sort((a, b) -> Long.compare((Long) b.get(K_TIME_MS), (Long) a.get(K_TIME_MS)));
        if (slowestTests.size() > 10) slowestTests = slowestTests.subList(0, 10);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE, true);
        result.put(K_STATUS, allPassed ? "PASSED" : "FAILED");
        result.put(K_TOTAL, totalTests);
        result.put("passed", totalTests - totalFailures - totalErrors - totalSkipped);
        result.put(K_FAILURES, totalFailures);
        result.put(K_ERRORS, totalErrors);
        result.put(K_SKIPPED, totalSkipped);
        result.put("time", String.format("%.2fs", totalTime));
        result.put("runAt", runAt);
        result.put("suites", suites);
        result.put("slowestTests", slowestTests);
        return result;
    }

    /**
     * Parsed representation of one surefire/failsafe {@code TEST-*.xml} suite.
     * Internal to the parser — exposed as a record so the main loop stays
     * one mutable accumulator per counter (Sonar S1141) rather than a nested
     * try/catch soup.
     */
    private record ParsedSuite(String shortName, Map<String, Object> display,
                               int tests, int failures, int errors, int skipped, double time,
                               List<double[]> testCaseTimes, List<String> testCaseNames) {}

    /**
     * Parses ONE TEST-*.xml stream. Malformed XML returns {@code null} so
     * the caller skips it — a single broken file shouldn't fail the whole
     * section.
     */
    private static ParsedSuite parseOneSuite(InputStream is, DocumentBuilder docBuilder) {
        try (is) {
            Document doc = docBuilder.parse(is);
            Element suite = doc.getDocumentElement();
            int tests    = ReportParsers.intAttr(suite, K_TESTS);
            int failures = ReportParsers.intAttr(suite, K_FAILURES);
            int errors   = ReportParsers.intAttr(suite, K_ERRORS);
            int skipped  = ReportParsers.intAttr(suite, K_SKIPPED);
            double time  = ReportParsers.doubleAttr(suite, "time");

            String fullName = suite.getAttribute("name");
            String shortName = fullName.contains(".")
                    ? fullName.substring(fullName.lastIndexOf('.') + 1)
                    : fullName;

            Map<String, Object> suiteMap = new LinkedHashMap<>();
            suiteMap.put("name", shortName);
            suiteMap.put(K_TESTS, tests);
            suiteMap.put(K_FAILURES, failures);
            suiteMap.put(K_ERRORS, errors);
            suiteMap.put(K_SKIPPED, skipped);
            suiteMap.put("time", String.format("%.3fs", time));

            NodeList testCases = doc.getElementsByTagName("testcase");
            List<double[]> tcTimes = new ArrayList<>(testCases.getLength());
            List<String>   tcNames = new ArrayList<>(testCases.getLength());
            for (int k = 0; k < testCases.getLength(); k++) {
                Element tc = (Element) testCases.item(k);
                tcTimes.add(new double[]{ReportParsers.doubleAttr(tc, "time")});
                tcNames.add(tc.getAttribute("classname") + "." + tc.getAttribute("name"));
            }
            return new ParsedSuite(shortName, suiteMap, tests, failures, errors, skipped, time,
                    tcTimes, tcNames);
        } catch (Exception ignored) {
            return null;  // malformed XML silently skipped
        }
    }

    /**
     * Loads every discoverable TEST-*.xml stream from classpath first,
     * filesystem fallback second. Empty list = no surefire reports found,
     * caller emits {@code {available: false}}.
     */
    @SuppressWarnings("java:S3776")
    private List<InputStream> loadSurefireStreams() {
        List<InputStream> streams = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:" + CP_SUREFIRE_PATTERN);
            for (Resource r : resources) {
                if (r.exists()) {
                    streams.add(r.getInputStream());
                }
            }
            if (!streams.isEmpty()) return streams;
        } catch (IOException ignored) {
            // fall through to dev fallback
        }
        File dir = new File(DEV_SUREFIRE_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] xmlFiles = dir.listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
            if (xmlFiles != null) {
                for (File f : xmlFiles) {
                    try {
                        streams.add(new java.io.FileInputStream(f));
                    } catch (IOException ignored) {
                        // skip unreadable files
                    }
                }
            }
        }
        return streams;
    }
}
