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
 * Parses PMD XML output into the {@code pmd} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildPmdSection}
 * under Phase B-1.
 *
 * <h3>Output</h3>
 * <pre>
 * { available, total,
 *   byRuleset: { X: N }, byPriority: { High: N, … },
 *   topRules: [ { rule, count } × up to 10 ],
 *   violations: [ { file, rule, ruleset, priority, message } × up to 50 ] }
 * </pre>
 */
@Component
public class PmdReportParser {

    private static final String CP_PMD  = "META-INF/build-reports/pmd.xml";
    private static final String DEV_PMD = "target/pmd.xml";

    private static final String K_AVAILABLE = "available";
    private static final String K_ERROR     = "error";
    private static final String K_TOTAL     = "total";
    private static final String K_PRIORITY  = "priority";
    private static final String K_MESSAGE   = "message";
    private static final String K_COUNT     = "count";

    // S3776: nested file→violation loops with several classification branches —
    // extracting sub-methods would split the accumulation logic.
    @SuppressWarnings("java:S3776")
    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_PMD, DEV_PMD);
        if (is == null) return Map.of(K_AVAILABLE, false);

        int total = 0;
        Map<String, Integer> byRuleset  = new LinkedHashMap<>();
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        Map<String, Integer> byRule     = new LinkedHashMap<>();
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

                NodeList viols = file.getElementsByTagName("violation");
                for (int j = 0; j < viols.getLength(); j++) {
                    Element v = (Element) viols.item(j);
                    String rule     = v.getAttribute("rule");
                    String ruleset  = v.getAttribute("ruleset");
                    String priority = v.getAttribute(K_PRIORITY);
                    String msg      = v.getTextContent().trim();

                    total++;
                    byRuleset.merge(ruleset, 1, Integer::sum);
                    byPriority.merge(priorityLabel(priority), 1, Integer::sum);
                    byRule.merge(rule, 1, Integer::sum);

                    if (violations.size() < 50) {
                        Map<String, Object> vmap = new LinkedHashMap<>();
                        vmap.put("file",     shortFile);
                        vmap.put("rule",     rule);
                        vmap.put("ruleset",  ruleset);
                        vmap.put(K_PRIORITY, priority);
                        vmap.put(K_MESSAGE,  msg.length() > 120 ? msg.substring(0, 120) + "…" : msg);
                        violations.add(vmap);
                    }
                }
            }
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        List<Map<String, Object>> topRules = byRule.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .map(e -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rule", e.getKey());
                m.put(K_COUNT, e.getValue());
                return m;
            })
            .toList();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE, true);
        r.put(K_TOTAL, total);
        r.put("byRuleset",  byRuleset);
        r.put("byPriority", byPriority);
        r.put("topRules",   topRules);
        r.put("violations", violations);
        return r;
    }

    private static String priorityLabel(String priority) {
        return switch (priority) {
            case "1" -> "High";
            case "2" -> "Normal";
            case "3" -> "Low";
            default  -> priority;
        };
    }
}
