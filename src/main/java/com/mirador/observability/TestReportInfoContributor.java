package com.mirador.observability;

import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Exposes Maven Surefire test results via /actuator/info under the "tests" key.
 *
 * <p>Reads target/surefire-reports/TEST-*.xml at actuator info refresh time. Returns an empty map
 * if no reports are found (e.g. after a clean build without running tests).
 *
 * <p>Useful for the Angular observability dashboard to display the last unit test run summary
 * without running a separate CI pipeline.
 */
@Component
public class TestReportInfoContributor implements InfoContributor {

    private static final String REPORTS_DIR = "target/surefire-reports";
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void contribute(Info.Builder builder) {
        File dir = new File(REPORTS_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            builder.withDetail("tests", Map.of("available", false));
            return;
        }

        File[] xmlFiles = dir.listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
        if (xmlFiles == null || xmlFiles.length == 0) {
            builder.withDetail("tests", Map.of("available", false));
            return;
        }

        int totalTests = 0;
        int totalFailures = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0.0;
        long lastModified = 0;
        List<Map<String, Object>> suites = new ArrayList<>();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder docBuilder = factory.newDocumentBuilder();
            for (File xml : xmlFiles) {
                lastModified = Math.max(lastModified, xml.lastModified());
                try {
                    Document doc = docBuilder.parse(xml);
                    Element suite = doc.getDocumentElement();
                    int tests = intAttr(suite, "tests");
                    int failures = intAttr(suite, "failures");
                    int errors = intAttr(suite, "errors");
                    int skipped = intAttr(suite, "skipped");
                    double time = doubleAttr(suite, "time");

                    totalTests += tests;
                    totalFailures += failures;
                    totalErrors += errors;
                    totalSkipped += skipped;
                    totalTime += time;

                    // Short class name for display (strip package prefix)
                    String fullName = suite.getAttribute("name");
                    String shortName = fullName.contains(".")
                            ? fullName.substring(fullName.lastIndexOf('.') + 1)
                            : fullName;

                    Map<String, Object> suiteMap = new LinkedHashMap<>();
                    suiteMap.put("name", shortName);
                    suiteMap.put("tests", tests);
                    suiteMap.put("failures", failures);
                    suiteMap.put("errors", errors);
                    suiteMap.put("skipped", skipped);
                    suiteMap.put("time", String.format("%.3fs", time));
                    suites.add(suiteMap);
                } catch (Exception ignored) {
                    // skip malformed XML
                }
            }
        } catch (Exception e) {
            builder.withDetail("tests", Map.of("available", false, "error", e.getMessage()));
            return;
        }

        String runAt = lastModified > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(lastModified), ZoneId.systemDefault())
                        .format(TS_FMT)
                : "unknown";

        boolean allPassed = totalFailures == 0 && totalErrors == 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", true);
        result.put("status", allPassed ? "PASSED" : "FAILED");
        result.put("total", totalTests);
        result.put("passed", totalTests - totalFailures - totalErrors - totalSkipped);
        result.put("failures", totalFailures);
        result.put("errors", totalErrors);
        result.put("skipped", totalSkipped);
        result.put("time", String.format("%.2fs", totalTime));
        result.put("runAt", runAt);
        result.put("suites", suites);

        builder.withDetail("tests", result);
    }

    private static int intAttr(Element el, String attr) {
        try {
            return Integer.parseInt(el.getAttribute(attr));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double doubleAttr(Element el, String attr) {
        try {
            return Double.parseDouble(el.getAttribute(attr));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
