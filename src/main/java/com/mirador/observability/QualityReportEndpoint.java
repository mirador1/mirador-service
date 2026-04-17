package com.mirador.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.lang.management.ManagementFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Actuator endpoint exposing a Maven quality report at {@code /actuator/quality}.
 *
 * <p>Aggregates build and quality data into a single JSON response consumed by the
 * Angular quality dashboard ({@code /quality} route in mirador-ui). Each section is
 * built by a private {@code build*Section()} method and returns either the data map
 * (with {@code available: true}) or {@code {available: false, reason: "..."}} when the
 * data source is absent or unreachable — so the UI can show a helpful message instead
 * of an error.
 *
 * <h3>Sections returned by {@link #report()}</h3>
 * <ul>
 *   <li><b>tests</b>        — Surefire XML: test counts, failures, slowest tests</li>
 *   <li><b>coverage</b>     — JaCoCo CSV: line/branch coverage %, per-class table</li>
 *   <li><b>bugs</b>         — SpotBugs XML: bug count, rank, category breakdown</li>
 *   <li><b>pmd</b>          — PMD XML: rule violation count and category breakdown</li>
 *   <li><b>checkstyle</b>   — Checkstyle XML: violation count by severity</li>
 *   <li><b>owasp</b>        — OWASP Dependency-Check JSON: CVE list with CVSS scores</li>
 *   <li><b>pitest</b>       — PIT XML: mutation test strength %</li>
 *   <li><b>sonar</b>        — SonarCloud REST API: bug/vuln/smell counts, ratings A–E</li>
 *   <li><b>build</b>        — build-info.properties: version, artifact, build time</li>
 *   <li><b>git</b>          — git log: last commit, branch, remote URL</li>
 *   <li><b>api</b>          — Spring MVC handler mappings: endpoint count, method breakdown</li>
 *   <li><b>dependencies</b> — pom.xml: direct dependency count, Spring Boot version</li>
 *   <li><b>metrics</b>      — Micrometer registry: metric count, key gauges/counters</li>
 *   <li><b>runtime</b>      — JVM: uptime, active profiles, heap, JAR layer list</li>
 *   <li><b>pipeline</b>     — GitLab API: last 10 CI/CD pipeline runs with status/duration</li>
 *   <li><b>branches</b>     — git for-each-ref: 20 most recently active remote branches</li>
 * </ul>
 *
 * <p>Data sources in priority order:
 * <ol>
 *   <li>Classpath resources (META-INF/build-reports/...) — present when the JAR was built with
 *       {@code mvn verify} and the reports were copied by the Antrun plugin.</li>
 *   <li>Filesystem fallback (target/...) — used during local development without packaging.</li>
 * </ol>
 */
@Component
@Endpoint(id = "quality")
public class QualityReportEndpoint {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Classpath paths (inside packaged JAR)
    private static final String CP_JACOCO = "META-INF/build-reports/jacoco.csv";
    private static final String CP_POM = "META-INF/build-reports/pom.xml";
    private static final String CP_SPOTBUGS = "META-INF/build-reports/spotbugsXml.xml";
    private static final String CP_DEP_TREE     = "META-INF/build-reports/dependency-tree.txt";
    private static final String CP_DEP_ANALYZE  = "META-INF/build-reports/dependency-analysis.txt";
    private static final String CP_THIRD_PARTY  = "META-INF/build-reports/THIRD-PARTY.txt";
    private static final String CP_SUREFIRE_PATTERN = "META-INF/build-reports/surefire/TEST-*.xml";
    private static final String CP_BUILD_INFO = "META-INF/build-info.properties";
    private static final String CP_PMD        = "META-INF/build-reports/pmd.xml";
    private static final String CP_CHECKSTYLE = "META-INF/build-reports/checkstyle-result.xml";
    private static final String CP_OWASP      = "META-INF/build-reports/dependency-check-report.json";
    private static final String CP_PITEST     = "META-INF/build-reports/pit-reports/mutations.xml";

    // Fallback paths for local development. DEV_JACOCO prefers the merged
    // report (unit + IT) — same data as the jacoco:check gate reads — and
    // falls back to the unit-only CSV when running `mvn verify -DskipITs`.
    private static final String DEV_JACOCO = "target/site/jacoco-merged/jacoco.csv";
    private static final String DEV_JACOCO_UNIT = "target/site/jacoco/jacoco.csv";
    private static final String DEV_SPOTBUGS = "target/spotbugsXml.xml";
    private static final String DEV_SUREFIRE_DIR = "target/surefire-reports";
    private static final String DEV_PMD        = "target/pmd.xml";
    private static final String DEV_CHECKSTYLE = "target/checkstyle-result.xml";
    private static final String DEV_OWASP      = "target/dependency-check-report.json";
    private static final String DEV_PITEST     = "target/pit-reports/mutations.xml";

    // ── Map key constants — used repeatedly across section builders ─────────────
    // Centralised here to avoid Sonar CRITICAL java:S1192 (duplicate string literals ≥3 occurrences).
    private static final String K_AVAILABLE   = "available";
    private static final String K_ERROR       = "error";
    private static final String K_TOTAL       = "total";
    private static final String K_FAILURES    = "failures";
    private static final String K_ERRORS      = "errors";
    private static final String K_TIME_MS     = "timeMs";
    private static final String K_PRIORITY    = "priority";
    private static final String K_METHODS     = "methods";
    private static final String K_MESSAGE     = "message";
    private static final String K_SEVERITY    = "severity";
    private static final String K_SCORE       = "score";
    private static final String K_DESCRIPTION = "description";
    private static final String K_COMPLEXITY  = "complexity";
    private static final String K_VULNERABILITIES = "vulnerabilities";
    private static final String K_TESTS         = "tests";
    private static final String K_COVERAGE      = "coverage";
    private static final String K_SKIPPED       = "skipped";
    private static final String K_UNKNOWN       = "unknown";
    private static final String K_VERSION       = "version";
    private static final String K_DEPENDENCIES  = "dependencies";
    private static final String K_MUTATED_CLASS = "mutatedClass";
    private static final String K_BRANCHES       = "branches";
    private static final String K_STATUS         = "status";
    private static final String K_GROUP_ID       = "groupId";
    private static final String K_ARTIFACT_ID    = "artifactId";
    private static final String K_COUNT          = "count";
    private static final String K_REASON         = "reason";

    /**
     * Absolute path to the {@code git} binary, resolved once at class-init
     * from well-known container / distro locations. Using an absolute path
     * in {@code ProcessBuilder} sidesteps the Sonar S4036 hotspot that
     * fires on commands resolved via {@code $PATH} (which could in theory
     * include a user-writable directory). In our container the path is
     * {@code /usr/bin/git}; on a developer Mac it's typically under
     * Homebrew. When git isn't found we fall back to the bare command
     * name so the diagnostics still run (the `git` entries are optional
     * metadata in the quality report, not load-bearing).
     */
    private static final String GIT_BIN = resolveGitBinary();

    private static String resolveGitBinary() {
        for (String candidate : List.of(
                "/usr/bin/git",
                "/usr/local/bin/git",
                "/opt/homebrew/bin/git",
                "/bin/git")) {
            Path p = Paths.get(candidate);
            if (Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return "git";
    }

    // SonarQube integration — defaults work for local Docker setup.
    // Override via env vars: SONAR_HOST_URL, SONAR_PROJECT_KEY, SONAR_TOKEN.
    @Value("${sonar.host.url:http://localhost:9000}")
    private String sonarHostUrl;

    @Value("${sonar.projectKey:mirador}")
    private String sonarProjectKey;

    // Token for SonarQube API calls — empty = anonymous access (works when forceAuth=false).
    @Value("${sonar.token:}")
    private String sonarToken;

    // GitLab pipeline history — calls GET /projects/:id/pipelines to fetch the last 10 runs.
    // In production the GitLab project ID is injected from the CI variable GITLAB_PROJECT_ID.
    // GITLAB_API_TOKEN requires read_api scope (never write). Both default to blank = section disabled.
    @Value("${gitlab.host.url:https://gitlab.com}")
    private String gitlabHostUrl;

    @Value("${gitlab.project.id:}")
    private String gitlabProjectId;

    @Value("${gitlab.api.token:}")
    private String gitlabApiToken;

    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final Environment environment;
    private final StartupTimeTracker startupTimeTracker;

    public QualityReportEndpoint(RequestMappingHandlerMapping requestMappingHandlerMapping,
                                 Environment environment,
                                 StartupTimeTracker startupTimeTracker) {
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
        this.environment = environment;
        this.startupTimeTracker = startupTimeTracker;
    }

    /**
     * Builds and returns the full quality report as a JSON map.
     *
     * <p>Each section is computed independently; a failure in one section (e.g., SonarCloud
     * unreachable, GitLab token missing) returns {@code {available: false}} for that section
     * only — it does not prevent the other sections from being included.
     *
     * @apiNote The response is not cached. Each {@code GET /actuator/quality} call re-reads
     *          all data sources. For the Angular dashboard this is acceptable because the
     *          endpoint is only polled on explicit navigation to the quality page.
     */
    @ReadOperation
    public Map<String, Object> report() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", LocalDateTime.now().format(TS_FMT));
        result.put(K_TESTS, buildTestsSection());
        result.put(K_COVERAGE, buildCoverageSection());
        result.put("bugs", buildBugsSection());
        result.put("pmd",       buildPmdSection());
        result.put("checkstyle",buildCheckstyleSection());
        result.put("owasp",     buildOwaspSection());
        result.put("pitest",    buildPitestSection());
        result.put("sonar",     buildSonarSection());
        result.put("build", buildBuildSection());
        result.put("git", buildGitSection());
        result.put("api", buildApiSection());
        result.put(K_DEPENDENCIES, buildDependenciesSection());
        result.put("licenses", buildLicensesSection());
        result.put("metrics", buildMetricsSection());
        result.put("runtime", buildRuntimeSection());
        result.put("pipeline", buildPipelineSection());
        result.put(K_BRANCHES, buildBranchesSection());
        return result;
    }

    // -------------------------------------------------------------------------
    // Tests section
    // -------------------------------------------------------------------------

    // Sonar java:S3776: cognitive complexity is intentionally above 15 here.
    // This method parses multi-source test XML/CSV data with multiple conditional branches —
    // extracting sub-methods would break the data-accumulation loop without improving clarity.
    @SuppressWarnings("java:S3776")
    private Map<String, Object> buildTestsSection() {
        // Try classpath first
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
        // Deduplicate by simple class name — stale reports from old packages
        // (after a rename without mvn clean) would otherwise double-count.
        java.util.Set<String> seenShortNames = new java.util.LinkedHashSet<>();

        DocumentBuilder docBuilder;
        try {
            docBuilder = secureDocumentBuilder();
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }
        for (InputStream is : streams) {
            ParsedSuite p = parseOneSuite(is, docBuilder);
            if (p == null || !seenShortNames.add(p.shortName())) continue;  // malformed or duplicate
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

        // Build slowest tests list
        List<Map<String,Object>> slowestTests = new ArrayList<>();
        for (int i = 0; i < allTestCaseNames.size(); i++) {
            Map<String,Object> tc = new LinkedHashMap<>();
            tc.put("name", allTestCaseNames.get(i));
            tc.put("time", String.format("%.3fs", allTestCases.get(i)[0]));
            tc.put(K_TIME_MS, (long)(allTestCases.get(i)[0] * 1000));
            slowestTests.add(tc);
        }
        slowestTests.sort((a, b) -> Long.compare((Long)b.get(K_TIME_MS), (Long)a.get(K_TIME_MS)));
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

    // S3776: reads surefire reports from two locations (packaged classpath + dev fallback)
    // with their own try/catch to keep the dev-loop fast — extracting would fragment the
    // "prefer packaged, then local" fallback intent.
    @SuppressWarnings("java:S3776")
    private List<InputStream> loadSurefireStreams() {
        List<InputStream> streams = new ArrayList<>();
        // Try classpath (packaged JAR)
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:" + CP_SUREFIRE_PATTERN);
            for (Resource r : resources) {
                if (r.exists()) {
                    streams.add(r.getInputStream());
                }
            }
            if (!streams.isEmpty()) return streams;
        } catch (IOException _) {
            // fall through to dev fallback
        }
        // Fallback: local target/ directory
        File dir = new File(DEV_SUREFIRE_DIR);
        if (dir.exists() && dir.isDirectory()) {
            File[] xmlFiles = dir.listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
            if (xmlFiles != null) {
                for (File f : xmlFiles) {
                    try {
                        streams.add(new java.io.FileInputStream(f));
                    } catch (IOException _) {
                        // skip unreadable files
                    }
                }
            }
        }
        return streams;
    }

    // -------------------------------------------------------------------------
    // Coverage section
    // -------------------------------------------------------------------------

    // S1141 + S135: the inner try/catch and several `continue` statements are
    // idiomatic for "skip malformed CSV row, aggregate the rest" — restructuring
    // to one exit point would hide the data-validation cliff.
    @SuppressWarnings({"java:S3776", "java:S1141", "java:S135"})
    private Map<String, Object> buildCoverageSection() {
        // Prefer the merged report (unit + IT). In dev, fall through to
        // the unit-only CSV when the merged one was not produced (e.g.
        // `mvn verify -DskipITs`).
        InputStream is = loadResource(CP_JACOCO, DEV_JACOCO);
        if (is == null) {
            is = loadResource(CP_JACOCO, DEV_JACOCO_UNIT);
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
                if (header) { header = false; continue; } // skip header row
                // CSV columns: GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,
                //              BRANCH_MISSED,BRANCH_COVERED,LINE_MISSED,LINE_COVERED,
                //              COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,METHOD_COVERED
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

                    // Aggregate by package (col[1]), strip leading path segments for display
                    String pkg = cols[1].trim();
                    String pkgShort = pkg.isEmpty() ? "(default)" : pkg.replace('/', '.');
                    // Use last segment for display
                    String[] pkgParts = pkgShort.split("\\.");
                    String pkgDisplay = pkgParts[pkgParts.length - 1];

                    pkgData.merge(pkgDisplay, new long[]{lCovered, lMissed + lCovered, iCovered, iMissed + iCovered},
                            (a, b) -> new long[]{a[0] + b[0], a[1] + b[1], a[2] + b[2], a[3] + b[3]});
                } catch (NumberFormatException _) {
                    // skip malformed lines
                }
            }
        } catch (IOException e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        List<Map<String, Object>> packages = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : pkgData.entrySet()) {
            long[] d = entry.getValue();
            double instrPct = d[3] > 0 ? round1(100.0 * d[2] / d[3]) : 0.0;
            double linePct  = d[1] > 0 ? round1(100.0 * d[0] / d[1]) : 0.0;
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

    private Map<String, Object> counterMap(long covered, long total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("covered", covered);
        m.put(K_TOTAL, total);
        m.put("pct", total > 0 ? round1(100.0 * covered / total) : 0.0);
        return m;
    }

    // -------------------------------------------------------------------------
    // Bugs section
    // -------------------------------------------------------------------------

    private Map<String, Object> buildBugsSection() {
        InputStream is = loadResource(CP_SPOTBUGS, DEV_SPOTBUGS);
        if (is == null) {
            return Map.of(K_AVAILABLE, false);
        }

        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, Integer> byCategory = new LinkedHashMap<>();
        Map<String, Integer> byPriority = new LinkedHashMap<>();

        try (is) {
            DocumentBuilder docBuilder = secureDocumentBuilder();
            Document doc = docBuilder.parse(is);
            NodeList bugInstances = doc.getElementsByTagName("BugInstance");
            for (int i = 0; i < bugInstances.getLength(); i++) {
                Element bug = (Element) bugInstances.item(i);
                String category = bug.getAttribute("category");
                String priority  = bug.getAttribute(K_PRIORITY);
                String type      = bug.getAttribute("type");

                // Extract class name from nested <Class> element
                String className = "";
                NodeList classes = bug.getElementsByTagName("Class");
                if (classes.getLength() > 0) {
                    className = ((Element) classes.item(0)).getAttribute("classname");
                    // Short name
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

    private String priorityLabel(String priority) {
        return switch (priority) {
            case "1" -> "High";
            case "2" -> "Normal";
            case "3" -> "Low";
            default -> priority;
        };
    }

    // -------------------------------------------------------------------------
    // Build section
    // -------------------------------------------------------------------------

    private Map<String, Object> buildBuildSection() {
        ClassPathResource res = new ClassPathResource(CP_BUILD_INFO);
        if (!res.exists()) {
            return Map.of(K_AVAILABLE, false);
        }

        Properties props = new Properties();
        try (InputStream is = res.getInputStream()) {
            props.load(is);
        } catch (IOException e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE, true);
        result.put("artifact", props.getProperty("build.artifact", K_UNKNOWN));
        result.put(K_VERSION, props.getProperty("build.version", K_UNKNOWN));
        result.put("time", props.getProperty("build.time", K_UNKNOWN));
        result.put("javaVersion", System.getProperty("java.version", K_UNKNOWN));
        result.put("springBootVersion", props.getProperty("build.version", K_UNKNOWN));
        return result;
    }

    // -------------------------------------------------------------------------
    // Git section
    // -------------------------------------------------------------------------

    /**
     * Returns the {@code origin} remote URL, or {@code null} if git isn't available
     * or the repo has no origin. Extracted from {@link #buildGitSection()} so the
     * caller doesn't nest two try/catch blocks (Sonar S1141).
     */
    private String fetchGitRemoteUrl() {
        try {
            Process proc = new ProcessBuilder(GIT_BIN, "remote", "get-url", "origin")
                    .directory(new File("."))
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line = br.readLine();
                proc.waitFor();
                return (line != null && !line.isBlank()) ? line.trim() : null;
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception _) {
            return null;
        }
    }

    private Map<String, Object> buildGitSection() {
        // Remote URL is optional (kept out of the outer try to avoid Sonar S1141 nested-try).
        String remoteUrl = fetchGitRemoteUrl();
        try {
            Process proc = new ProcessBuilder(GIT_BIN, "log", "--no-merges", "-15",
                    "--format=%h|%an|%ai|%s")
                    .directory(new File("."))
                    .redirectErrorStream(true)
                    .start();
            List<Map<String,Object>> commits = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] parts = line.split("\\|", 4);
                    if (parts.length == 4) {
                        Map<String,Object> c = new LinkedHashMap<>();
                        c.put("hash", parts[0].trim());
                        c.put("author", parts[1].trim());
                        c.put("date", parts[2].trim().substring(0, 19)); // ISO date without timezone
                        c.put(K_MESSAGE, parts[3].trim());
                        commits.add(c);
                    }
                }
            }
            proc.waitFor();
            Map<String,Object> r = new LinkedHashMap<>();
            r.put(K_AVAILABLE, !commits.isEmpty());
            if (remoteUrl != null) r.put("remoteUrl", remoteUrl);
            r.put("commits", commits);
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // API section
    // -------------------------------------------------------------------------

    private Map<String, Object> buildApiSection() {
        List<Map<String,Object>> endpoints = new ArrayList<>();
        requestMappingHandlerMapping.getHandlerMethods().forEach((info, method) -> {
            Set<String> patterns = info.getPatternValues();
            Set<org.springframework.web.bind.annotation.RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                Map<String,Object> ep = new LinkedHashMap<>();
                ep.put("path", pattern);
                ep.put(K_METHODS, methods.isEmpty() ? List.of("GET") : methods.stream().map(Enum::name).sorted().toList());
                ep.put("handler", method.getBeanType().getSimpleName() + "." + method.getMethod().getName());
                endpoints.add(ep);
            }
        });
        // Sort by path then method
        endpoints.sort((a, b) -> ((String)a.get("path")).compareTo((String)b.get("path")));
        Map<String,Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE, true);
        r.put(K_TOTAL, endpoints.size());
        r.put("endpoints", endpoints);
        return r;
    }

    // -------------------------------------------------------------------------
    // Dependencies section
    // -------------------------------------------------------------------------

    /**
     * Parses direct dependencies from pom.xml, resolves {@code ${property}} version references,
     * then checks Maven Central for each pinned dependency to see if a newer version is available.
     *
     * <p>Freshness checks use the Maven Central Solr API in parallel (up to 20 deps, 8 s total timeout).
     * Managed dependencies ({@code version = "(managed)"}) are skipped — their version is controlled
     * by the Spring Boot BOM and upgrading them is a Spring Boot upgrade, not a direct dep change.
     *
     * @apiNote The total wall-clock time is bounded by 8 seconds regardless of how many deps are checked.
     *          Partial results are returned if some futures time out; those entries will lack
     *          {@code latestVersion} and {@code outdated} fields.
     */
    // S3776+S135+S6541: cognitive complexity + "brain method" flag are inherent
    // to the pom XML walk plus parallel HTTP freshness lookups; several
    // early-loop-skips handle parent references, dependencyManagement imports and
    // excluded scopes. Decomposing further would spread the data-accumulation
    // across several helpers without improving readability.
    @SuppressWarnings({"java:S3776", "java:S135", "java:S6541"})
    private Map<String, Object> buildDependenciesSection() {
        InputStream is = loadResource(CP_POM, "pom.xml");
        if (is == null) return Map.of(K_AVAILABLE, false);

        // Step 1: Parse pom.xml — extract properties + direct dependencies
        Map<String, String> pomProperties = new HashMap<>();
        List<Map<String,Object>> deps = new ArrayList<>();
        try (is) {
            DocumentBuilder db = secureNamespaceAwareDocumentBuilder();
            Document doc = db.parse(is);

            // Collect <properties> values to resolve ${property} version references
            NodeList propNodes = doc.getElementsByTagName("properties");
            for (int i = 0; i < propNodes.getLength(); i++) {
                Element propsEl = (Element) propNodes.item(i);
                // Only the top-level <properties> block (parent is <project>)
                if (!"project".equals(propsEl.getParentNode().getNodeName())) continue;
                NodeList children = propsEl.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    if (children.item(j) instanceof Element propEl) {
                        pomProperties.put(propEl.getTagName(), propEl.getTextContent().trim());
                    }
                }
            }

            // Collect direct dependencies
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element dep = (Element) depNodes.item(i);
                // Only direct dependencies (parent is <dependencies>, not <dependencyManagement>)
                if (!K_DEPENDENCIES.equals(dep.getParentNode().getNodeName())) continue;
                String groupId    = getTagText(dep, K_GROUP_ID);
                String artifactId = getTagText(dep, K_ARTIFACT_ID);
                String rawVersion = getTagText(dep, K_VERSION);
                String scope      = getTagText(dep, "scope");
                if (scope.isEmpty()) scope = "compile";

                // Resolve ${property} references
                String resolvedVersion = rawVersion;
                if (rawVersion.startsWith("${") && rawVersion.endsWith("}")) {
                    String key = rawVersion.substring(2, rawVersion.length() - 1);
                    resolvedVersion = pomProperties.getOrDefault(key, rawVersion);
                }

                Map<String,Object> d = new LinkedHashMap<>();
                d.put(K_GROUP_ID, groupId);
                d.put(K_ARTIFACT_ID, artifactId);
                d.put(K_VERSION, resolvedVersion.isEmpty() ? "(managed)" : resolvedVersion);
                d.put("scope", scope);
                deps.add(d);
            }
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        // Step 2: Check Maven Central for each dep with a resolvable version (skip managed + properties)
        // Limit to first 25 deps to keep total latency manageable
        HttpClient freshClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        // Use a single shared log instance to avoid Sonar S1312 per-call
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (Map<String,Object> dep : deps) {
            String version = (String) dep.get(K_VERSION);
            // Skip: managed by BOM, unresolved property reference, or non-release versions
            if (version == null || version.startsWith("(") || version.startsWith("${")) continue;
            if (futures.size() >= 25) break; // cap to avoid excessive parallel calls

            String g = (String) dep.get(K_GROUP_ID);
            String a = (String) dep.get(K_ARTIFACT_ID);
            String url = "https://search.maven.org/solrsearch/select?rows=1&wt=json&q="
                    + URLEncoder.encode("g:" + g + " AND a:" + a, StandardCharsets.UTF_8);

            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(5))
                            .header("Accept", "application/json")
                            .GET()
                            .build();
                    HttpResponse<String> resp = freshClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        JsonNode root = MAPPER.readTree(resp.body());
                        JsonNode docs = root.path("response").path("docs");
                        if (docs.isArray() && !docs.isEmpty()) {
                            String latest = docs.get(0).path("latestVersion").asText("");
                            if (!latest.isBlank()) {
                                dep.put("latestVersion", latest);
                                // Outdated = current version differs from latest AND current is not a SNAPSHOT/milestone
                                boolean outdated = !latest.equals(version)
                                        && !version.contains("SNAPSHOT")
                                        && !version.contains("-M")
                                        && !version.contains("-RC")
                                        && !version.contains("-alpha")
                                        && !version.contains("-beta");
                                dep.put("outdated", outdated);
                            }
                        }
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } catch (Exception _) {
                    // Freshness check failure is non-critical — dep appears without latestVersion field
                }
            });
            futures.add(f);
        }

        // Wait up to 8 s for all freshness checks (partial results if some time out)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(8, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (Exception _) {
            // Timeout or interruption — return whatever was collected so far
        }

        long outdatedCount = deps.stream()
                .filter(d -> Boolean.TRUE.equals(d.get("outdated")))
                .count();

        // Step 3: Dependency tree (generated by maven-dependency-plugin:tree at build time)
        Map<String,Object> treeResult = null;
        InputStream treeIs = loadResource(CP_DEP_TREE, "target/dependency-tree.txt");
        if (treeIs != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(treeIs, StandardCharsets.UTF_8))) {
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
                String rawTree = String.join("\n", lines);
                treeResult = new LinkedHashMap<>();
                treeResult.put(K_AVAILABLE, true);
                treeResult.put("tree", rawTree);
                // Transitive lines start with space/pipe/backslash/plus in the text tree format
                long transitiveCount = lines.stream()
                        .filter(l -> l.startsWith("   ") || l.startsWith("\\-") || l.startsWith("+"))
                        .count();
                treeResult.put("totalTransitive", transitiveCount);
            } catch (IOException _) {
                // tree unavailable — report without it
            }
        }

        // Step 4: Dependency analysis (maven-dependency-plugin:analyze-only) — used-undeclared
        //         and unused-declared dependencies. Helps identify dependency hygiene issues.
        Map<String,Object> analyzeResult = parseDependencyAnalysis();

        Map<String,Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE, true);
        r.put(K_TOTAL, deps.size());
        r.put("outdatedCount", outdatedCount);
        if (treeResult != null) r.put("dependencyTree", treeResult);
        if (analyzeResult != null) r.put("dependencyAnalysis", analyzeResult);
        r.put(K_DEPENDENCIES, deps);
        return r;
    }

    /**
     * Parses the output of {@code maven-dependency-plugin:analyze-only} packaged at
     * {@value #CP_DEP_ANALYZE}. The file contains two sections separated by blank lines:
     * <pre>
     *   Used undeclared dependencies found:
     *      group:artifact:jar:version:scope
     *   Unused declared dependencies found:
     *      group:artifact:jar:version:scope
     * </pre>
     *
     * @return map with {@code usedUndeclared} and {@code unusedDeclared} lists, or {@code null}
     *         if the file is absent (not generated yet / build skipped analyze phase).
     */
    // S1168: null is semantically distinct from Map.of() here — the caller uses it
    // to decide whether to include the "dependencyAnalysis" key in the report at all.
    // Returning an empty map would emit `"dependencyAnalysis": {}` even when the
    // source file is missing, misleading the UI.
    @SuppressWarnings({"java:S3776", "java:S1168"})
    private Map<String,Object> parseDependencyAnalysis() {
        InputStream is = loadResource(CP_DEP_ANALYZE, "target/dependency-analysis.txt");
        if (is == null) return null;

        List<String> usedUndeclared = new ArrayList<>();
        List<String> unusedDeclared = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String section = null;
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.contains("Used undeclared")) {
                    section = "used";
                } else if (trimmed.contains("Unused declared")) {
                    section = "unused";
                } else if (trimmed.startsWith("com.") || trimmed.startsWith("org.") ||
                           trimmed.startsWith("io.") || trimmed.startsWith("net.") ||
                           trimmed.startsWith("jakarta.") || trimmed.startsWith("javax.") ||
                           trimmed.startsWith("ch.") || trimmed.startsWith("de.")) {
                    // Dependency coordinate line: group:artifact:jar:version:scope
                    if ("used".equals(section))   usedUndeclared.add(trimmed);
                    if ("unused".equals(section)) unusedDeclared.add(trimmed);
                }
            }
        } catch (IOException _) {
            return null;
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE, true);
        result.put("usedUndeclared", usedUndeclared);
        result.put("usedUndeclaredCount", usedUndeclared.size());
        result.put("unusedDeclared", unusedDeclared);
        result.put("unusedDeclaredCount", unusedDeclared.size());
        return result;
    }

    private String getTagText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() > 0 && nl.item(0).getParentNode() == parent) {
            return nl.item(0).getTextContent().trim();
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // Licenses section
    // -------------------------------------------------------------------------

    /**
     * Parses the THIRD-PARTY.txt generated by {@code license-maven-plugin:add-third-party}
     * (packaged at {@value #CP_THIRD_PARTY}). Each line has the format:
     * <pre>
     *   (License Name) groupId:artifactId:version - Display Name
     * </pre>
     *
     * <p>Returns a map with:
     * <ul>
     *   <li>{@code total} — total number of third-party dependencies listed</li>
     *   <li>{@code licenses} — list of {@code {license, count, incompatible}} grouped by license name</li>
     *   <li>{@code incompatibleCount} — count of GPL/AGPL/CDDL/EPL dependencies</li>
     *   <li>{@code dependencies} — flat list of each dep with {@code group}, {@code artifact},
     *       {@code version}, {@code license}, {@code incompatible}</li>
     * </ul>
     *
     * <p>Incompatible licenses for commercial projects: GPL, AGPL, LGPL, CDDL, EPL.
     */
    @SuppressWarnings({"java:S3776", "java:S135"})   // THIRD-PARTY.txt parsing: multiple early-skip branches for header and malformed rows
    private Map<String,Object> buildLicensesSection() {
        InputStream is = loadResource(CP_THIRD_PARTY, "target/THIRD-PARTY.txt");
        if (is == null) return Map.of(K_AVAILABLE, false);

        // Licenses that may be incompatible with proprietary/commercial use
        List<String> restrictedKeywords = List.of("GPL", "AGPL", "LGPL", "CDDL", "EPL");

        List<Map<String,Object>> deps = new ArrayList<>();
        Map<String,Integer> licenseCounts = new LinkedHashMap<>();
        int incompatibleCount = 0;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                // THIRD-PARTY.txt lines: "(License Name) group:artifact:version - Name"
                if (!trimmed.startsWith("(")) continue;
                int closeIdx = trimmed.indexOf(')');
                if (closeIdx < 0) continue;
                String licenseStr = trimmed.substring(1, closeIdx).trim();
                String rest = trimmed.substring(closeIdx + 1).trim();
                // rest: "group:artifact:version - Display Name" or just coords
                String coords = rest.contains(" - ") ? rest.substring(0, rest.indexOf(" - ")).trim() : rest.trim();
                String[] parts = coords.split(":");
                if (parts.length < 2) continue;
                String groupId    = parts[0];
                String artifactId = parts[1];
                String version    = parts.length >= 3 ? parts[2] : "";
                boolean incompatible = restrictedKeywords.stream()
                        .anyMatch(kw -> licenseStr.toUpperCase().contains(kw));
                if (incompatible) incompatibleCount++;
                licenseCounts.merge(licenseStr, 1, Integer::sum);
                Map<String,Object> dep = new LinkedHashMap<>();
                dep.put("group", groupId);
                dep.put("artifact", artifactId);
                dep.put(K_VERSION, version);
                dep.put("license", licenseStr);
                dep.put("incompatible", incompatible);
                deps.add(dep);
            }
        } catch (IOException e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        // Build license summary sorted by count desc
        List<Map<String,Object>> licenseSummary = licenseCounts.entrySet().stream()
                .sorted(Map.Entry.<String,Integer>comparingByValue().reversed())
                .map(e -> {
                    boolean restricted = restrictedKeywords.stream()
                            .anyMatch(kw -> e.getKey().toUpperCase().contains(kw));
                    return Map.<String,Object>of("license", e.getKey(), K_COUNT, e.getValue(), "incompatible", restricted);
                })
                .toList();

        Map<String,Object> result = new LinkedHashMap<>();
        result.put(K_AVAILABLE, true);
        result.put(K_TOTAL, deps.size());
        result.put("incompatibleCount", incompatibleCount);
        result.put("licenses", licenseSummary);
        result.put(K_DEPENDENCIES, deps);
        return result;
    }

    // -------------------------------------------------------------------------
    // Metrics section
    // -------------------------------------------------------------------------

    /**
     * Reads JaCoCo CSV to compute:
     * <ul>
     *   <li>Totals: class/method/line/complexity counts across the whole project.</li>
     *   <li>Package-level summary sorted by complexity (for the Metrics tab).</li>
     *   <li>Top-10 most complex classes (for the Cyclomatic Complexity view).</li>
     *   <li>Classes with 0% method coverage (potential gap in test suite).</li>
     * </ul>
     *
     * <p>JaCoCo CSV columns (0-indexed):
     * GROUP(0), PACKAGE(1), CLASS(2), INSTRUCTION_MISSED(3), INSTRUCTION_COVERED(4),
     * BRANCH_MISSED(5), BRANCH_COVERED(6), LINE_MISSED(7), LINE_COVERED(8),
     * COMPLEXITY_MISSED(9), COMPLEXITY_COVERED(10), METHOD_MISSED(11), METHOD_COVERED(12)
     */
    // S1141 + S135 + S3776: intentional skip-malformed-row pattern — same rationale as
    // buildCoverageSection above. Restructuring obscures the validation intent.
    @SuppressWarnings({"java:S1141", "java:S135", "java:S3776"})
    private Map<String, Object> buildMetricsSection() {
        // Same merged-first / unit-fallback strategy as buildCoverageSection.
        InputStream is = loadResource(CP_JACOCO, DEV_JACOCO);
        if (is == null) {
            is = loadResource(CP_JACOCO, DEV_JACOCO_UNIT);
        }
        if (is == null) return Map.of(K_AVAILABLE, false);

        long totalClasses = 0;
        long totalMethods = 0;
        long totalLines = 0;
        long totalComplexity = 0;
        Map<String, long[]> pkgMetrics = new LinkedHashMap<>(); // [classes, lines, methods, complexity]
        // Class-level complexity for top-10 view
        List<long[]> classComplexity = new ArrayList<>(); // [complexity, classNameIndex]
        List<String> classNames = new ArrayList<>();
        // Classes with 0% method coverage (METHOD_COVERED=0, METHOD_TOTAL>0)
        List<String> untestedClasses = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = br.readLine()) != null) {
                if (header) { header = false; continue; }
                String[] cols = line.split(",", -1);
                if (cols.length < 13) continue;
                try {
                    long lineMissed   = Long.parseLong(cols[7].trim());
                    long lineCovered  = Long.parseLong(cols[8].trim());
                    long cxMissed     = Long.parseLong(cols[9].trim());
                    long cxCovered    = Long.parseLong(cols[10].trim());
                    long methodMissed = Long.parseLong(cols[11].trim());
                    long methodCovered= Long.parseLong(cols[12].trim());
                    long lines      = lineMissed + lineCovered;
                    long methods    = methodMissed + methodCovered;
                    long complexity = cxMissed + cxCovered;

                    totalClasses++;
                    totalMethods    += methods;
                    totalLines      += lines;
                    totalComplexity += complexity;

                    // Package-level aggregate
                    String pkg = cols[1].trim();
                    String[] parts = pkg.replace('/', '.').split("\\.");
                    String pkgShort = parts[parts.length - 1];
                    pkgMetrics.merge(pkgShort, new long[]{1, lines, methods, complexity},
                        (a, b) -> new long[]{a[0]+1, a[1]+b[1], a[2]+b[2], a[3]+b[3]});

                    // Class-level record for top-10 most complex
                    String rawClass = cols[2].trim();
                    // Use simple class name (strip inner class separators like $1, $Companion)
                    String simpleClass = rawClass.contains("$") ? rawClass.substring(0, rawClass.indexOf('$')) : rawClass;
                    int idx = classNames.size();
                    classNames.add(simpleClass);
                    classComplexity.add(new long[]{complexity, idx});

                    // Untested class: has methods but 0 are covered (METHOD_MISSED > 0 && METHOD_COVERED == 0)
                    // Excludes pure data classes (records, DTOs) with 0 methods — they have no logic to test.
                    if (methodCovered == 0 && methods > 0) {
                        untestedClasses.add(simpleClass);
                    }
                } catch (NumberFormatException _) {
                    // Skip malformed rows silently — JaCoCo CSV occasionally leaks
                    // non-numeric totals on synthetic classes; they shouldn't break the report.
                }
            }
        } catch (IOException e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        List<Map<String,Object>> packages = new ArrayList<>();
        for (Map.Entry<String, long[]> e : pkgMetrics.entrySet()) {
            long[] v = e.getValue();
            Map<String,Object> p = new LinkedHashMap<>();
            p.put("name", e.getKey());
            p.put("classes", v[0]);
            p.put("lines", v[1]);
            p.put(K_METHODS, v[2]);
            p.put(K_COMPLEXITY, v[3]);
            packages.add(p);
        }
        // Sort by complexity desc (top 10 most complex packages first)
        packages.sort((a, b) -> Long.compare((Long)b.get(K_COMPLEXITY), (Long)a.get(K_COMPLEXITY)));

        // Top-10 most complex classes — sorted desc, deduplicated by simple name (inner classes merged)
        classComplexity.sort((a, b) -> Long.compare(b[0], a[0]));
        List<Map<String,Object>> topComplex = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (long[] cc : classComplexity) {
            String name = classNames.get((int) cc[1]);
            if (seen.add(name) && topComplex.size() < 10) {
                Map<String,Object> entry = new LinkedHashMap<>();
                entry.put("class", name);
                entry.put(K_COMPLEXITY, cc[0]);
                topComplex.add(entry);
            }
        }

        Map<String,Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE, true);
        r.put("totalClasses", totalClasses);
        r.put("totalMethods", totalMethods);
        r.put("totalLines", totalLines);
        r.put("totalComplexity", totalComplexity);
        r.put("packages", packages);
        // Top-10 most complex classes by cyclomatic complexity (COMPLEXITY_MISSED + COMPLEXITY_COVERED)
        r.put("topComplexClasses", topComplex);
        // Classes with 0% method coverage — potential test gaps (deduplicated, sorted alphabetically)
        java.util.Set<String> untestedSet = new java.util.TreeSet<>(untestedClasses);
        r.put("untestedClasses", new ArrayList<>(untestedSet));
        r.put("untestedCount", untestedSet.size());
        return r;
    }

    // -------------------------------------------------------------------------
    // PMD section
    // -------------------------------------------------------------------------

    // Sonar java:S3776: cognitive complexity is intentionally above 15 here.
    // Parses PMD XML with nested file→violation loops and multiple classification branches.
    // Extracting sub-methods would split the violation-accumulation logic across multiple methods
    // without making the code clearer.
    @SuppressWarnings("java:S3776")
    private Map<String, Object> buildPmdSection() {
        InputStream is = loadResource(CP_PMD, DEV_PMD);
        if (is == null) return Map.of(K_AVAILABLE, false);

        int total = 0;
        Map<String, Integer> byRuleset  = new LinkedHashMap<>();
        Map<String, Integer> byPriority = new LinkedHashMap<>();
        Map<String, Integer> byRule     = new LinkedHashMap<>();
        List<Map<String, Object>> violations = new ArrayList<>();

        try (is) {
            DocumentBuilder db = secureDocumentBuilder();
            Document doc = db.parse(is);

            NodeList files = doc.getElementsByTagName("file");
            for (int i = 0; i < files.getLength(); i++) {
                Element file = (Element) files.item(i);
                String filename = file.getAttribute("name");
                // Short class name from path
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

                    if (violations.size() < 50) { // limit to first 50
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

        // Sort byRule by count desc, keep top 10
        List<Map<String, Object>> topRules = byRule.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(10)
            .map(e -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("rule", e.getKey()); m.put(K_COUNT, e.getValue()); return m; })
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

    // -------------------------------------------------------------------------
    // Checkstyle section
    // -------------------------------------------------------------------------

    // Sonar java:S3776: cognitive complexity is intentionally above 15 here.
    // Parses Checkstyle XML with nested file→error loops and severity/checker classification.
    @SuppressWarnings("java:S3776")
    private Map<String, Object> buildCheckstyleSection() {
        InputStream is = loadResource(CP_CHECKSTYLE, DEV_CHECKSTYLE);
        if (is == null) return Map.of(K_AVAILABLE, false);

        int total = 0;
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byChecker  = new LinkedHashMap<>();
        List<Map<String, Object>> violations = new ArrayList<>();

        try (is) {
            DocumentBuilder db = secureDocumentBuilder();
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

                    // Short checker name: last segment of FQCN
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
            .map(e -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("checker", e.getKey()); m.put(K_COUNT, e.getValue()); return m; })
            .toList();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE,   true);
        r.put(K_TOTAL,       total);
        r.put("bySeverity",  bySeverity);
        r.put("topCheckers", topCheckers);
        r.put("violations",  violations);
        return r;
    }

    // -------------------------------------------------------------------------
    // OWASP section
    // -------------------------------------------------------------------------

    private Map<String, Object> buildOwaspSection() {
        InputStream is = loadResource(CP_OWASP, DEV_OWASP);
        if (is == null) return Map.of(K_AVAILABLE, false);

        int total = 0;
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        List<Map<String, Object>> vulns = new ArrayList<>();

        try (is) {
            JsonNode root = MAPPER.readTree(is);
            JsonNode dependencies = root.path(K_DEPENDENCIES);
            for (JsonNode dep : dependencies) {
                JsonNode vulnerabilities = dep.path(K_VULNERABILITIES);
                if (vulnerabilities.isEmpty()) continue;

                String depName = dep.path("fileName").asText(K_UNKNOWN);
                for (JsonNode vuln : vulnerabilities) {
                    String rawName  = vuln.path("name").asText("?");
                    String name     = cleanCveId(rawName, vuln.path("references"));
                    String severity = vuln.path(K_SEVERITY).asText("UNKNOWN").toUpperCase();
                    double score    = vuln.path("cvssv3").path("baseScore").asDouble(
                                      vuln.path("cvssv2").path(K_SCORE).asDouble(0.0));
                    String desc     = cleanCveDescription(
                            vuln.path(K_DESCRIPTION).asText(rawName)); // fallback to name if no desc

                    total++;
                    bySeverity.merge(severity, 1, Integer::sum);

                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("cve",        name);
                    v.put(K_SEVERITY,   severity);
                    v.put(K_SCORE,      score);
                    v.put("dependency", depName);
                    v.put(K_DESCRIPTION, desc);
                    vulns.add(v);
                }
            }
            // Sort by score desc
            vulns.sort((a, b) -> Double.compare((Double)b.get(K_SCORE), (Double)a.get(K_SCORE)));
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE,   true);
        r.put(K_TOTAL,       total);
        r.put("bySeverity",  bySeverity);
        r.put(K_VULNERABILITIES, vulns.size() > 30 ? vulns.subList(0, 30) : vulns);
        return r;
    }

    // -------------------------------------------------------------------------
    // Pitest section
    // -------------------------------------------------------------------------

    private Map<String, Object> buildPitestSection() {
        InputStream is = loadResource(CP_PITEST, DEV_PITEST);
        if (is == null) return Map.of(K_AVAILABLE, false, "note", "Run: mvn test-compile pitest:mutationCoverage");

        int total = 0;
        int killed = 0;
        int survived = 0;
        int noCoverage = 0;
        Map<String, Integer> byMutator  = new LinkedHashMap<>();
        Map<String, Integer> byStatus   = new LinkedHashMap<>();
        List<Map<String, Object>> surviving = new ArrayList<>();

        try (is) {
            DocumentBuilder db = secureDocumentBuilder();
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
                        Map<String,Object> sm = new LinkedHashMap<>();
                        sm.put("class",  getTagText(m, K_MUTATED_CLASS).contains(".")
                            ? getTagText(m, K_MUTATED_CLASS).substring(getTagText(m, K_MUTATED_CLASS).lastIndexOf('.')+1)
                            : getTagText(m, K_MUTATED_CLASS));
                        sm.put("method", getTagText(m, "mutatedMethod"));
                        sm.put("mutator", mutator);
                        sm.put(K_DESCRIPTION, getTagText(m, K_DESCRIPTION));
                        surviving.add(sm);
                    }}
                    case "NO_COVERAGE" -> noCoverage++;
                    default            -> { /* TIMED_OUT, RUN_ERROR, etc. — count only */ }
                }
            }
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }

        double score = total > 0 ? round1(100.0 * killed / total) : 0.0;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE,    true);
        r.put(K_TOTAL,        total);
        r.put("killed",       killed);
        r.put("survived",     survived);
        r.put("noCoverage",   noCoverage);
        r.put(K_SCORE,        score);
        r.put("byStatus",     byStatus);
        r.put("byMutator",    byMutator);
        r.put("survivingMutations", surviving);
        return r;
    }

    // -------------------------------------------------------------------------
    // SonarQube section
    // -------------------------------------------------------------------------

    /**
     * Fetches key quality metrics from the local SonarQube instance via its REST API.
     *
     * <p>Uses java.net.http.HttpClient (built-in since Java 11) with a 3-second timeout
     * to avoid blocking the actuator endpoint when SonarQube is not running.
     * If the token is empty and forceAuthentication is false, anonymous access works.
     *
     * @apiNote Metrics fetched: bugs, vulnerabilities, code_smells, coverage,
     *          duplicated_lines_density, reliability_rating, security_rating,
     *          sqale_rating (maintainability), ncloc (lines of code).
     * @implNote Rating values are 1–5 (A–E); converted to letter grades here.
     */
    private Map<String, Object> buildSonarSection() {
        String metricsKey = "bugs,vulnerabilities,code_smells,coverage,"
                + "duplicated_lines_density,reliability_rating,security_rating,sqale_rating,ncloc";
        String apiUrl = sonarHostUrl + "/api/measures/component"
                + "?component=" + sonarProjectKey
                + "&metricKeys=" + metricsKey;

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET();

            // Use Bearer token if configured, else anonymous (works when forceAuth=false)
            if (sonarToken != null && !sonarToken.isBlank()) {
                reqBuilder.header("Authorization", "Bearer " + sonarToken);
            }

            HttpResponse<String> resp = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 404) {
                // Project key not found — analysis has not been run yet
                return Map.of(K_AVAILABLE, false,
                        "note", "Project '" + sonarProjectKey + "' not found — run ./run.sh sonar first");
            }
            if (resp.statusCode() != 200) {
                return Map.of(K_AVAILABLE, false, "note", "HTTP " + resp.statusCode());
            }

            JsonNode root = MAPPER.readTree(resp.body());
            JsonNode measures = root.path("component").path("measures");

            Map<String, String> raw = new java.util.HashMap<>();
            for (JsonNode m : measures) {
                raw.put(m.path("metric").asText(), m.path("value").asText(""));
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put(K_AVAILABLE,             true);
            r.put("projectKey",            sonarProjectKey);
            r.put("url",                   sonarHostUrl + "/dashboard?id=" + sonarProjectKey);
            r.put("bugs",                  parseIntOrNull(raw.get("bugs")));
            r.put(K_VULNERABILITIES,       parseIntOrNull(raw.get(K_VULNERABILITIES)));
            r.put("codeSmells",            parseIntOrNull(raw.get("code_smells")));
            r.put(K_COVERAGE,              parseDoubleOrNull(raw.get(K_COVERAGE)));
            r.put("duplications",          parseDoubleOrNull(raw.get("duplicated_lines_density")));
            r.put("linesOfCode",           parseIntOrNull(raw.get("ncloc")));
            r.put("reliabilityRating",     ratingLabel(raw.get("reliability_rating")));
            r.put("securityRating",        ratingLabel(raw.get("security_rating")));
            r.put("maintainabilityRating", ratingLabel(raw.get("sqale_rating")));
            return r;

        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Map.of(K_AVAILABLE, false,
                    "note", "SonarQube call interrupted");
        } catch (Exception _) {
            return Map.of(K_AVAILABLE, false,
                    "note", "SonarQube unreachable — start with: docker compose up -d sonarqube");
        }
    }

    /** Converts a SonarQube numeric rating (1–5) to a letter grade (A–E). */
    private static String ratingLabel(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim()) {
            case "1.0", "1" -> "A";
            case "2.0", "2" -> "B";
            case "3.0", "3" -> "C";
            case "4.0", "4" -> "D";
            case "5.0", "5" -> "E";
            default -> value;
        };
    }

    private static Integer parseIntOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException _) { return null; }
    }

    private static Double parseDoubleOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return round1(Double.parseDouble(v.trim())); } catch (NumberFormatException _) { return null; }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Loads a resource from classpath first, falling back to a local file path.
     * Returns null if neither is found.
     */
    private InputStream loadResource(String classpathPath, String devFallback) {
        ClassPathResource res = new ClassPathResource(classpathPath);
        if (res.exists()) {
            try {
                return res.getInputStream();
            } catch (IOException _) {
                // fall through
            }
        }
        File devFile = new File(devFallback);
        if (devFile.exists()) {
            try {
                return new java.io.FileInputStream(devFile);
            } catch (IOException _) {
                // fall through
            }
        }
        return null;
    }

    /**
     * Parses an ISO-8601 start/finish pair and returns the number of whole seconds
     * between them. Extracted so {@link #buildPipelineHistorySection()} doesn't need
     * a nested try/catch (Sonar S1141). A malformed timestamp simply omits the field.
     */
    private static java.util.OptionalLong parseDurationSeconds(String startIso, String finishIso) {
        try {
            Instant start  = Instant.parse(startIso);
            Instant finish = Instant.parse(finishIso);
            return java.util.OptionalLong.of(Duration.between(start, finish).getSeconds());
        } catch (Exception _) {
            return java.util.OptionalLong.empty();
        }
    }

    /**
     * Parsed representation of one surefire/failsafe {@code TEST-*.xml} suite.
     * Used by {@link #parseOneSuite(InputStream, DocumentBuilder)} so the caller
     * doesn't need a second try/catch inside the loop (Sonar S1141).
     */
    private record ParsedSuite(String shortName, Map<String, Object> display,
                                int tests, int failures, int errors, int skipped, double time,
                                List<double[]> testCaseTimes, List<String> testCaseNames) {}

    private static ParsedSuite parseOneSuite(InputStream is, DocumentBuilder docBuilder) {
        try (is) {
            Document doc = docBuilder.parse(is);
            Element suite = doc.getDocumentElement();
            int tests    = intAttr(suite, K_TESTS);
            int failures = intAttr(suite, K_FAILURES);
            int errors   = intAttr(suite, K_ERRORS);
            int skipped  = intAttr(suite, K_SKIPPED);
            double time  = doubleAttr(suite, "time");

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
                tcTimes.add(new double[]{doubleAttr(tc, "time")});
                tcNames.add(tc.getAttribute("classname") + "." + tc.getAttribute("name"));
            }
            return new ParsedSuite(shortName, suiteMap, tests, failures, errors, skipped, time,
                    tcTimes, tcNames);
        } catch (Exception _) {
            return null;  // malformed XML silently skipped
        }
    }

    private static int intAttr(Element el, String attr) {
        try {
            return Integer.parseInt(el.getAttribute(attr));
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static double doubleAttr(Element el, String attr) {
        try {
            return Double.parseDouble(el.getAttribute(attr));
        } catch (NumberFormatException _) {
            return 0.0;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /**
     * Returns a short, displayable identifier for a vulnerability.
     * Proper CVE IDs (CVE-YYYY-NNNNN) are returned as-is.
     * RetireJS/GHSA advisories put the full Markdown description in the name field —
     * for those we extract the GHSA-xxxx ID from references, or return a short summary.
     */
    static String cleanCveId(String rawName, JsonNode references) {
        if (rawName == null || rawName.isBlank()) return "UNKNOWN";
        // Proper CVE ID
        if (rawName.matches("CVE-\\d{4}-\\d+")) return rawName;
        // Look for GHSA-xxxx in references first
        if (references != null) {
            for (JsonNode ref : references) {
                String url = ref.path("url").asText("");
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("(GHSA-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4})")
                        .matcher(url);
                if (m.find()) return m.group(1);
            }
        }
        // Fallback: first non-empty, non-markdown line, truncated
        String first = rawName.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#") && !l.startsWith("```"))
                .findFirst().orElse(rawName);
        return first.length() > 40 ? first.substring(0, 40) + "…" : first;
    }

    /**
     * Cleans a CVE description from the NVD JSON for display.
     * Some descriptions contain full Markdown with HTML code examples
     * (e.g. DOMPurify PoC descriptions). We extract only the first
     * meaningful plain-text sentence/paragraph.
     */
    // S3776+S135: multi-step text cleaning with interleaved break/continue
    // to skip headers, code fences and empty lines while preserving the first
    // real paragraph — extracting further would obscure the flow.
    @SuppressWarnings({"java:S3776", "java:S135"})
    static String cleanCveDescription(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // Strip markdown section headers (lines starting with #)
        // and take content of the first non-empty, non-header paragraph
        String[] lines = raw.split("\n");
        StringBuilder first = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#") || trimmed.startsWith("```") || trimmed.startsWith("- ")) {
                // If we already have content, stop here
                if (!first.isEmpty()) break;
                continue; // skip leading headers/code blocks
            }
            if (trimmed.isEmpty()) {
                if (!first.isEmpty()) break; // end of first paragraph
                continue;
            }
            if (!first.isEmpty()) first.append(" ");
            first.append(trimmed);
        }
        String result = first.toString()
                // Strip inline markdown: bold **x**, italic *x*, code `x`, links [text](url)
                .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                .replaceAll("\\*([^*]+)\\*", "$1")
                .replaceAll("`([^`]+)`", "$1")
                .replaceAll("\\[([^]]+)]\\([^)]+\\)", "$1")
                // Strip any residual HTML tags
                .replaceAll("<[^>]+>", "")
                .trim();
        return result.length() > 200 ? result.substring(0, 200) + "…" : result;
    }

    // -------------------------------------------------------------------------
    // Runtime section — active profiles, JVM uptime, Spring Boot startup time
    // -------------------------------------------------------------------------

    private Map<String, Object> buildRuntimeSection() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE, true);

        // Active Spring profiles (e.g., ["dev", "docker"] or ["default"])
        String[] active = environment.getActiveProfiles();
        r.put("activeProfiles", active.length == 0 ? new String[]{"default"} : active);

        // JVM uptime — how long the process has been running
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long uptimeSec = uptimeMs / 1000;
        r.put("uptimeSeconds", uptimeSec);
        r.put("uptimeHuman", formatUptime(uptimeSec));

        // JVM start time
        long startMs = ManagementFactory.getRuntimeMXBean().getStartTime();
        r.put("startedAt", java.time.Instant.ofEpochMilli(startMs)
                .atZone(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // Spring Boot startup duration — time from JVM launch to ApplicationReady event.
        // Matches "Started MiradorApplication in X.XXX seconds" in the boot log.
        long startupMs = startupTimeTracker.getStartupDurationMs();
        if (startupMs > 0) {
            r.put("startupDurationMs", startupMs);
            r.put("startupDurationSeconds", startupMs / 1000.0);
        }

        // JAR layer sizes — read BOOT-INF/layers.idx from the running JAR
        r.put("jarLayers", buildJarLayersSection());

        return r;
    }

    private String formatUptime(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    /**
     * Returns a DocumentBuilder hardened against XXE (XML External Entity) attacks.
     * SonarQube BLOCKER rule java:S2755 — all XML parsing in this class uses this factory.
     *
     * <p>Disables DOCTYPE declarations entirely ({@code disallow-doctype-decl}); any XML document
     * that contains a DOCTYPE declaration will throw a SAXParseException rather than silently
     * loading external resources.  This is the recommended defence-in-depth strategy for
     * read-only tooling parsers that never need entity resolution.
     */
    private static DocumentBuilder secureDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Disallow DOCTYPE — prevents all XXE, SSRF and billion-laughs variants.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    /** Variant that also enables namespace-aware mode (needed for pom.xml parsing). */
    private static DocumentBuilder secureNamespaceAwareDocumentBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder();
    }

    private List<Map<String, Object>> buildJarLayersSection() {
        // Spring Boot fat JARs contain BOOT-INF/layers.idx listing each layer.
        // We read it from the classpath (it's present both when running from JAR and exploded).
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("BOOT-INF/layers.idx")) {
            if (is == null) return List.of();
            List<Map<String, Object>> layers = new ArrayList<>();
            String current = null;
            int count = 0;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("- ")) {
                        // Layer header: "- 'dependencies':"
                        if (current != null) {
                            Map<String, Object> l = new LinkedHashMap<>();
                            l.put("name", current);
                            l.put("entries", count);
                            layers.add(l);
                        }
                        current = line.substring(3, line.length() - 2); // strip "- '" and "':"
                        count = 0;
                    } else if (line.startsWith("  - ")) {
                        count++;
                    }
                }
            }
            if (current != null) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("name", current);
                l.put("entries", count);
                layers.add(l);
            }
            return layers;
        } catch (Exception _) {
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Pipeline history section — GitLab API
    // -------------------------------------------------------------------------

    /**
     * Fetches the last 10 CI/CD pipeline runs from the GitLab API.
     *
     * <p>Requires {@code GITLAB_PROJECT_ID} and {@code GITLAB_API_TOKEN} (read_api scope)
     * to be set. Returns {@code available=false} gracefully when either is blank,
     * so local dev mode works without configuring GitLab credentials.
     *
     * <p>The GitLab REST API path is:
     * {@code GET /api/v4/projects/:id/pipelines?per_page=10&order_by=id&sort=desc}
     */
    private Map<String, Object> buildPipelineSection() {
        if (gitlabProjectId.isBlank()) {
            return Map.of(K_AVAILABLE, false, K_REASON, "GITLAB_PROJECT_ID not configured");
        }

        String url = gitlabHostUrl + "/api/v4/projects/" + gitlabProjectId
                + "/pipelines?per_page=10&order_by=id&sort=desc";
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (!gitlabApiToken.isBlank()) {
            reqBuilder.header("PRIVATE-TOKEN", gitlabApiToken);
        }

        try {
            HttpResponse<String> resp = client.send(reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return Map.of(K_AVAILABLE, false, K_ERROR,
                        "GitLab API returned HTTP " + resp.statusCode());
            }
            JsonNode root = MAPPER.readTree(resp.body());
            List<Map<String, Object>> pipelines = new ArrayList<>();
            for (JsonNode p : root) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id",        p.path("iid").asInt());
                entry.put("ref",       p.path("ref").asText("-"));
                entry.put(K_STATUS,    p.path(K_STATUS).asText("-"));
                entry.put("createdAt", p.path("created_at").asText("-"));
                // Duration — only available after pipeline completes
                JsonNode startedAt  = p.path("started_at");
                JsonNode finishedAt = p.path("finished_at");
                if (!startedAt.isNull() && !finishedAt.isNull()
                        && !startedAt.isMissingNode() && !finishedAt.isMissingNode()) {
                    // Helper call avoids a nested try/catch in the outer HTTP try (Sonar S1141).
                    parseDurationSeconds(startedAt.asText(), finishedAt.asText())
                            .ifPresent(secs -> entry.put("durationSeconds", secs));
                }
                entry.put("webUrl", p.path("web_url").asText(""));
                pipelines.add(entry);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(K_AVAILABLE, true);
            result.put("projectId", gitlabProjectId);
            result.put("host", gitlabHostUrl);
            result.put("pipelines", pipelines);
            return result;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Map.of(K_AVAILABLE, false, K_ERROR, "interrupted");
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }
    }

    /**
     * Lists remote branches with their last-commit date and author.
     *
     * Uses `git for-each-ref refs/remotes --sort=-committerdate` which is available
     * in the JAR's working directory (the build includes the .git folder for actuator/info).
     * Falls back gracefully when .git is absent (e.g., Docker without mounted source).
     *
     * Format field: %(refname:short) %(committerdate:iso) %(authorname)
     */
    // S135: a couple of `continue` statements skip malformed git output lines —
    // collapsing them into nested ifs hides the "row-is-invalid, next" intent.
    @SuppressWarnings("java:S135")
    private Map<String, Object> buildBranchesSection() {
        try {
            // --sort=-committerdate: most recently updated branches first
            // %(refname:short): strips "origin/" prefix cleanly
            Process proc = new ProcessBuilder(
                    GIT_BIN, "for-each-ref", "refs/remotes",
                    "--sort=-committerdate",
                    "--format=%(refname:short)|%(committerdate:iso)|%(authorname)",
                    "--count=20"
            ).redirectErrorStream(true).start();

            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();

            if (output.isBlank()) {
                return Map.of(K_AVAILABLE, false, K_REASON, "No remote branches found (git unavailable or no remotes)");
            }

            List<Map<String, Object>> branches = new ArrayList<>();
            for (String line : output.split("\n")) {
                String[] parts = line.split("\\|", 3);
                if (parts.length < 2) continue;
                String name = parts[0].trim();
                // Skip HEAD pointer — it's not a real branch
                if (name.endsWith("/HEAD")) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("lastCommit", parts.length > 1 ? parts[1].trim() : "");
                entry.put("author",     parts.length > 2 ? parts[2].trim() : "");
                branches.add(entry);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put(K_AVAILABLE, true);
            result.put(K_BRANCHES, branches);
            result.put(K_TOTAL, branches.size());
            return result;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return Map.of(K_AVAILABLE, false, K_REASON, "git call interrupted");
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_REASON, "git error: " + e.getMessage());
        }
    }
}
