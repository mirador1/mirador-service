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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
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
    // Sonar java:S1192 — these keys appear in both per-suite maps and the totals map.
    private static final String KEY_AVAILABLE = "available";
    private static final String KEY_TESTS    = "tests";
    private static final String KEY_FAILURES = "failures";
    private static final String KEY_ERRORS   = "errors";
    private static final String KEY_SKIPPED  = "skipped";
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void contribute(Info.Builder builder) {
        File dir = new File(REPORTS_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            builder.withDetail(KEY_TESTS, Map.of(KEY_AVAILABLE, false));
            return;
        }

        File[] xmlFiles = dir.listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
        if (xmlFiles == null || xmlFiles.length == 0) {
            builder.withDetail(KEY_TESTS, Map.of(KEY_AVAILABLE, false));
            return;
        }

        int totalTests = 0;
        int totalFailures = 0;
        int totalErrors = 0;
        int totalSkipped = 0;
        double totalTime = 0.0;
        long lastModified = 0;
        List<Map<String, Object>> suites = new ArrayList<>();

        try {
            DocumentBuilder docBuilder = secureDocumentBuilder();
            for (File xml : xmlFiles) {
                lastModified = Math.max(lastModified, xml.lastModified());
                try {
                    Document doc = docBuilder.parse(xml);
                    Element suite = doc.getDocumentElement();
                    int tests = intAttr(suite, KEY_TESTS);
                    int failures = intAttr(suite, KEY_FAILURES);
                    int errors = intAttr(suite, KEY_ERRORS);
                    int skipped = intAttr(suite, KEY_SKIPPED);
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
                    suiteMap.put(KEY_TESTS, tests);
                    suiteMap.put(KEY_FAILURES, failures);
                    suiteMap.put(KEY_ERRORS, errors);
                    suiteMap.put(KEY_SKIPPED, skipped);
                    suiteMap.put("time", String.format("%.3fs", time));
                    suites.add(suiteMap);
                } catch (Exception ignored) {
                    // skip malformed XML
                }
            }
        } catch (Exception e) {
            builder.withDetail(KEY_TESTS, Map.of(KEY_AVAILABLE, false, "error", e.getMessage()));
            return;
        }

        String runAt = lastModified > 0
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(lastModified), ZoneId.systemDefault())
                        .format(TS_FMT)
                : "unknown";

        boolean allPassed = totalFailures == 0 && totalErrors == 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(KEY_AVAILABLE, true);
        result.put("status", allPassed ? "PASSED" : "FAILED");
        result.put("total", totalTests);
        result.put("passed", totalTests - totalFailures - totalErrors - totalSkipped);
        result.put(KEY_FAILURES, totalFailures);
        result.put(KEY_ERRORS, totalErrors);
        result.put(KEY_SKIPPED, totalSkipped);
        result.put("time", String.format("%.2fs", totalTime));
        result.put("runAt", runAt);
        result.put("suites", suites);

        builder.withDetail(KEY_TESTS, result);
    }

    /**
     * Returns a DocumentBuilder hardened against XXE attacks (SonarQube java:S2755).
     * Disables DOCTYPE declarations so no external entities can be loaded.
     */
    private static DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
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
