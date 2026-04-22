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
 * Parses SpotBugs XML output into the {@code bugs} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildBugsSection}
 * under Phase B-1.
 *
 * <h3>Data sources</h3>
 * <ol>
 *   <li>Classpath {@code META-INF/build-reports/spotbugsXml.xml} (packaged).</li>
 *   <li>Filesystem {@code target/spotbugsXml.xml} (dev).</li>
 * </ol>
 *
 * <h3>Output</h3>
 * <pre>
 * { available: true,
 *   total, byCategory: { X: N }, byPriority: { High: N, … },
 *   items: [ { category, priority, type, className } … ] }
 * </pre>
 *
 * <p>Uses {@link ReportParsers#secureDocumentBuilder} — XML is external
 * build-tool output so XXE-hardening is mandatory.
 */
@Component
public class SpotBugsReportParser {

    private static final String CP_SPOTBUGS  = "META-INF/build-reports/spotbugsXml.xml";
    private static final String DEV_SPOTBUGS = "target/spotbugsXml.xml";

    private static final String K_AVAILABLE = "available";
    private static final String K_ERROR     = "error";
    private static final String K_TOTAL     = "total";
    private static final String K_PRIORITY  = "priority";

    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_SPOTBUGS, DEV_SPOTBUGS);
        if (is == null) {
            return Map.of(K_AVAILABLE, false);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> byPriority = new LinkedHashMap<>();

        try (is) {
            DocumentBuilder docBuilder = ReportParsers.secureDocumentBuilder();
            Document doc = docBuilder.parse(is);
            NodeList bugInstances = doc.getElementsByTagName("BugInstance");
            for (int i = 0; i < bugInstances.getLength(); i++) {
                Element bug = (Element) bugInstances.item(i);
                String category = bug.getAttribute("category");
                String priority = bug.getAttribute(K_PRIORITY);
                String type     = bug.getAttribute("type");

                // Extract the reporting class name from the first nested <Class>
                String className = "";
                NodeList classes = bug.getElementsByTagName("Class");
                if (classes.getLength() > 0) {
                    className = ((Element) classes.item(0)).getAttribute("classname");
                    if (className.contains(".")) {
                        className = className.substring(className.lastIndexOf('.') + 1);
                    }
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category", category);
                item.put(K_PRIORITY, priority);
                item.put("type", type);
                item.put("className", className);
                items.add(item);

                byCategory.merge(category, 1, Integer::sum);
                byPriority.merge(priorityLabel(priority), 1, Integer::sum);
            }
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE, true);
        result.put(K_TOTAL, items.size());
        result.put("byCategory", byCategory);
        result.put("byPriority", byPriority);
        result.put("items", items);
        return result;
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
