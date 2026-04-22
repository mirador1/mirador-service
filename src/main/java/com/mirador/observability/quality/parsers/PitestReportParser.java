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
 * Parses PIT (mutation testing) XML output into the {@code pitest} section.
 *
 * <p>Extracted 2026-04-22 from {@code QualityReportEndpoint.buildPitestSection}
 * under Phase B-1.
 *
 * <h3>Output</h3>
 * <pre>
 * { available, total, killed, survived, noCoverage, score,
 *   byStatus: { KILLED: N, SURVIVED: N, NO_COVERAGE: N, … },
 *   byMutator: { ConditionalsBoundaryMutator: N, … },
 *   survivingMutations: [ { class, method, mutator, description } × up to 20 ] }
 * </pre>
 * Returns {@code {available: false, note: "Run: mvn …"}} when no PIT
 * reports found — useful to nudge devs who haven't run mutation testing.
 */
@Component
public class PitestReportParser {

    private static final String CP_PITEST  = "META-INF/build-reports/pit-reports/mutations.xml";
    private static final String DEV_PITEST = "target/pit-reports/mutations.xml";

    private static final String K_AVAILABLE     = "available";
    private static final String K_ERROR         = "error";
    private static final String K_TOTAL         = "total";
    private static final String K_STATUS        = "status";
    private static final String K_SCORE         = "score";
    private static final String K_DESCRIPTION   = "description";
    private static final String K_MUTATED_CLASS = "mutatedClass";

    // S3776: counts + switch accumulator in one pass.
    @SuppressWarnings("java:S3776")
    public Map<String, Object> parse() {
        InputStream is = ReportParsers.loadResource(CP_PITEST, DEV_PITEST);
        if (is == null) {
            return Map.of(K_AVAILABLE, false, "note", "Run: mvn test-compile pitest:mutationCoverage");
        }

        int total = 0;
        int killed = 0;
        int survived = 0;
        int noCoverage = 0;
        Map<String, Integer> byMutator = new LinkedHashMap<>();
        Map<String, Integer> byStatus  = new LinkedHashMap<>();
        List<Map<String, Object>> surviving = new ArrayList<>();

        try (is) {
            DocumentBuilder db = ReportParsers.secureDocumentBuilder();
            Document doc = db.parse(is);
            NodeList mutations = doc.getElementsByTagName("mutation");
            for (int i = 0; i < mutations.getLength(); i++) {
                Element m = (Element) mutations.item(i);
                String status  = m.getAttribute(K_STATUS);
                String mutator = m.getAttribute("mutator");
                if (mutator.contains(".")) mutator = mutator.substring(mutator.lastIndexOf('.') + 1);

                total++;
                byStatus.merge(status, 1, Integer::sum);
                byMutator.merge(mutator, 1, Integer::sum);

                switch (status) {
                    case "KILLED"      -> killed++;
                    case "SURVIVED"    -> { survived++; if (surviving.size() < 20) {
                        Map<String, Object> sm = new LinkedHashMap<>();
                        String mutatedClass = ReportParsers.getTagText(m, K_MUTATED_CLASS);
                        sm.put("class",  mutatedClass.contains(".")
                            ? mutatedClass.substring(mutatedClass.lastIndexOf('.') + 1)
                            : mutatedClass);
                        sm.put("method", ReportParsers.getTagText(m, "mutatedMethod"));
                        sm.put("mutator", mutator);
                        sm.put(K_DESCRIPTION, ReportParsers.getTagText(m, K_DESCRIPTION));
                        surviving.add(sm);
                    }}
                    case "NO_COVERAGE" -> noCoverage++;
                    default            -> { /* TIMED_OUT, RUN_ERROR etc. — count only */ }
                }
            }
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        double score = total > 0 ? ReportParsers.round1(100.0 * killed / total) : 0.0;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE,          true);
        r.put(K_TOTAL,              total);
        r.put("killed",             killed);
        r.put("survived",           survived);
        r.put("noCoverage",         noCoverage);
        r.put(K_SCORE,              score);
        r.put("byStatus",           byStatus);
        r.put("byMutator",          byMutator);
        r.put("survivingMutations", surviving);
        return r;
    }
}
