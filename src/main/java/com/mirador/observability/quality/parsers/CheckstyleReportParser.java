package com.mirador.observability.quality.parsers;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Parses Checkstyle XML output into the {@code checkstyle} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildCheckstyleSection}
 * under Phase B-1.
 *
 * <h3>Output</h3>
 * <pre>
 * { available, total,
 *   bySeverity: { error: N, warning: N },
 *   topCheckers: [ { checker, count } × up to 10 ],
 *   violations: [ { file, line, severity, checker, message } × up to 50 ] }
 * </pre>
 */
@Component
public class CheckstyleReportParser {

    private static final String CP_CHECKSTYLE  = "META-INF/build-reports/checkstyle-result.xml";
    private static final String DEV_CHECKSTYLE = "target/checkstyle-result.xml";

    private static final String K_AVAILABLE = "available";
    private static final String K_ERROR     = "error";
    private static final String K_TOTAL     = "total";
    private static final String K_SEVERITY  = "severity";
    private static final String K_MESSAGE   = "message";
    private static final String K_COUNT     = "count";

    // S3776: nested file→error loop with severity/checker classification.
    @SuppressWarnings("java:S3776")
    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_CHECKSTYLE, DEV_CHECKSTYLE);
        if (is == null) return Map.of(K_AVAILABLE, false);

        int total = 0;
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byChecker  = new LinkedHashMap<>();
        List<Map<String, Object>> violations = new ArrayList<>();

        try (is) {
            DocumentBuilder db = ReportParsers.secureDocumentBuilder();
            Document doc = db.parse(is);
            NodeList files = doc.getElementsByTagName("file");
            for (int i = 0; i < files.getLength(); i++) {
                Element file = (Element) files.item(i);
                String filename = file.getAttribute("name");
                String shortFile = filename.contains("/")
                    ? filename.substring(filename.lastIndexOf('/') + 1).replace(".java", "")
                    : filename;

                NodeList errors = file.getElementsByTagName(K_ERROR);
                for (int j = 0; j < errors.getLength(); j++) {
                    Element err = (Element) errors.item(j);
                    String severity = err.getAttribute(K_SEVERITY);
                    String source   = err.getAttribute("source");
                    String message  = err.getAttribute(K_MESSAGE);
                    String line     = err.getAttribute("line");

                    // Short checker name: last FQCN segment
                    String checker = source.contains(".")
                        ? source.substring(source.lastIndexOf('.') + 1)
                        : source;

                    total++;
                    bySeverity.merge(severity, 1, Integer::sum);
                    byChecker.merge(checker, 1, Integer::sum);

                    if (violations.size() < 50) {
                        Map<String, Object> vmap = new LinkedHashMap<>();
                        vmap.put("file",     shortFile);
                        vmap.put("line",     line);
                        vmap.put(K_SEVERITY, severity);
                        vmap.put("checker",  checker);
                        vmap.put(K_MESSAGE,  message.length() > 100 ? message.substring(0, 100) + "…" : message);
                        violations.add(vmap);
                    }
                }
            }
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        List<Map<String, Object>> topCheckers = byChecker.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("checker", e.getKey());
                m.put(K_COUNT, e.getValue());
                return m;
            })
            .toList();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE,   true);
        r.put(K_TOTAL,       total);
        r.put("bySeverity",  bySeverity);
        r.put("topCheckers", topCheckers);
        r.put("violations",  violations);
        return r;
    }
}
