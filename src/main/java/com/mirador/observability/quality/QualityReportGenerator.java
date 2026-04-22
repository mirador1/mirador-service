package com.mirador.observability.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mirador.observability.quality.parsers.CheckstyleReportParser;
import com.mirador.observability.quality.parsers.JacocoReportParser;
import com.mirador.observability.quality.parsers.OwaspReportParser;
import com.mirador.observability.quality.parsers.PitestReportParser;
import com.mirador.observability.quality.parsers.PmdReportParser;
import com.mirador.observability.quality.parsers.SpotBugsReportParser;
import com.mirador.observability.quality.parsers.SurefireReportParser;
import com.mirador.observability.quality.providers.DependenciesSectionProvider;
import com.mirador.observability.quality.providers.LicensesSectionProvider;
import com.mirador.observability.quality.providers.MetricsSectionProvider;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Build-time CLI that assembles every file-based quality-report section into a
 * single JSON artifact. Runs at Maven {@code prepare-package} phase via the
 * {@code exec-maven-plugin} binding in {@code pom.xml}.
 *
 * <p>Why this exists — ADR-0052 Phase Q-2:
 * {@code QualityReportEndpoint} used to parse Surefire XML / JaCoCo CSV /
 * SpotBugs XML / PMD XML / Checkstyle XML / OWASP JSON / PIT XML /
 * pom.xml / THIRD-PARTY.txt on every {@code GET /actuator/quality} call.
 * That coupled the runtime backend to 9 build-tool output shapes and
 * kept Jackson / javax.xml.parsers / HttpClient on the runtime classpath
 * to serve a DEV-oriented dashboard. ADR-0026 ("no awareness of
 * third-party tools") was being quietly violated.
 *
 * <p>This generator relocates all parsing to the build phase — the JVM
 * that serves traffic reads one opaque JSON file, no parser dependency.
 * Tool awareness stays in the Maven build, where it belongs.
 *
 * <h3>Output</h3>
 * Writes {@code target/classes/META-INF/quality-build-report.json}:
 * <pre>
 * { "generatedAt": "yyyy-MM-dd HH:mm:ss",
 *   "tests":        { … surefire … },
 *   "coverage":     { … jacoco … },
 *   "bugs":         { … spotbugs … },
 *   "pmd":          { … pmd … },
 *   "checkstyle":   { … checkstyle … },
 *   "owasp":        { … owasp … },
 *   "pitest":       { … pit … },
 *   "dependencies": { … pom + maven-central freshness … },
 *   "licenses":     { … THIRD-PARTY.txt … },
 *   "metrics":      { … jacoco CSV → per-package complexity + top-10 + untested … } }
 * </pre>
 *
 * <h3>Runtime behaviour</h3>
 * Each section returns {@code {available: false}} when its source file
 * is missing at build time (e.g. running {@code mvn package -DskipTests}
 * skips the test reports). The UI tolerates absent sections, so a
 * partial JSON is always a valid output.
 *
 * <h3>Why pure POJOs (no Spring)</h3>
 * The 7 parsers + 3 providers are instantiated with {@code new …()} rather
 * than via {@code @Autowired}. They have no Spring-wired dependencies
 * (no {@code ApplicationContext}, {@code Environment}, or HandlerMapping).
 * Running {@code main()} without a Spring context keeps build-time memory
 * + startup cost to a minimum.
 */
public final class QualityReportGenerator {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String OUTPUT_PATH = "target/classes/META-INF/quality-build-report.json";

    private QualityReportGenerator() { /* CLI entry-point only */ }

    /**
     * Entry point wired to {@code exec-maven-plugin} at {@code prepare-package}.
     * Exits 0 always — a missing parser input is reported as
     * {@code {available: false}} in the output JSON, not a hard failure
     * (the build-tool output isn't guaranteed: {@code mvn package -DskipTests}
     * produces no surefire reports; skipping PIT produces no mutations.xml).
     *
     * <p>Hard failure only on IO write error — if we can't write the output
     * file, the downstream JAR won't contain the report and the endpoint
     * will return {@code {available: false}} for every section. That's a
     * surprising state worth failing the build over.
     */
    public static void main(String[] args) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt",  LocalDateTime.now().format(TS_FMT));

        // Parsers — 7 tool outputs, each self-contained POJO.
        report.put("tests",        new SurefireReportParser().parse());
        report.put("coverage",     new JacocoReportParser().parse());
        report.put("bugs",         new SpotBugsReportParser().parse());
        report.put("pmd",          new PmdReportParser().parse());
        report.put("checkstyle",   new CheckstyleReportParser().parse());
        report.put("owasp",        new OwaspReportParser().parse());
        report.put("pitest",       new PitestReportParser().parse());

        // File-based providers — pom.xml walk (+ Maven Central freshness, here
        // at build time is legitimate) + THIRD-PARTY.txt license summary +
        // JaCoCo CSV re-read for per-package complexity metrics (Q-2b).
        report.put("dependencies", new DependenciesSectionProvider().parse());
        report.put("licenses",     new LicensesSectionProvider().parse());
        report.put("metrics",      new MetricsSectionProvider().parse());

        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        File output = new File(OUTPUT_PATH);
        // target/classes/META-INF should exist after compile, but create
        // defensively so the build still works when this goal is bound to
        // an earlier phase (e.g. generate-resources) during debugging.
        //noinspection ResultOfMethodCallIgnored — mkdirs is idempotent, we check existence via writeValue
        output.getParentFile().mkdirs();
        mapper.writeValue(output, report);

        System.out.println("[quality-build-report] Wrote " + OUTPUT_PATH + " (" + output.length() + " bytes)");
    }
}
