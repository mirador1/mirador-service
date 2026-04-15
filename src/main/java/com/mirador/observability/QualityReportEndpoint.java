package com.mirador.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
 * Actuator endpoint exposing a Maven quality report at /actuator/quality.
 *
 * <p>Aggregates data from:
 * <ul>
 *   <li>Surefire XML reports (META-INF/build-reports/surefire/TEST-*.xml)</li>
 *   <li>JaCoCo CSV report (META-INF/build-reports/jacoco.csv)</li>
 *   <li>SpotBugs XML report (META-INF/build-reports/spotbugsXml.xml)</li>
 *   <li>Build info properties (META-INF/build-info.properties)</li>
 * </ul>
 *
 * <p>Falls back to target/ directory paths when classpath resources are not present
 * (useful during local development before packaging).
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
    private static final String CP_SUREFIRE_PATTERN = "META-INF/build-reports/surefire/TEST-*.xml";
    private static final String CP_BUILD_INFO = "META-INF/build-info.properties";
    private static final String CP_PMD        = "META-INF/build-reports/pmd.xml";
    private static final String CP_CPD        = "META-INF/build-reports/cpd.xml";
    private static final String CP_CHECKSTYLE = "META-INF/build-reports/checkstyle-result.xml";
    private static final String CP_OWASP      = "META-INF/build-reports/dependency-check-report.json";
    private static final String CP_PITEST     = "META-INF/build-reports/pit-reports/mutations.xml";

    // Fallback paths for local development
    private static final String DEV_JACOCO = "target/site/jacoco/jacoco.csv";
    private static final String DEV_SPOTBUGS = "target/spotbugsXml.xml";
    private static final String DEV_SUREFIRE_DIR = "target/surefire-reports";
    private static final String DEV_PMD        = "target/pmd.xml";
    private static final String DEV_CPD        = "target/cpd.xml";
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

    public QualityReportEndpoint(RequestMappingHandlerMapping requestMappingHandlerMapping,
                                 Environment environment) {
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
        this.environment = environment;
    }

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
        result.put("metrics", buildMetricsSection());
        result.put("runtime", buildRuntimeSection());
        result.put("pipeline", buildPipelineSection());
        result.put("branches", buildBranchesSection());
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

        try {
            DocumentBuilder docBuilder = secureDocumentBuilder();
            for (InputStream is : streams) {
                try (is) {
                    Document doc = docBuilder.parse(is);
                    Element suite = doc.getDocumentElement();
                    int tests = intAttr(suite, K_TESTS);
                    int failures = intAttr(suite, K_FAILURES);
                    int errors = intAttr(suite, K_ERRORS);
                    int skipped = intAttr(suite, K_SKIPPED);
                    double time = doubleAttr(suite, "time");

                    String fullName = suite.getAttribute("name");
                    String shortName = fullName.contains(".")
                            ? fullName.substring(fullName.lastIndexOf('.') + 1)
                            : fullName;

                    // Skip duplicate class names (e.g. old package + new package same class)
                    if (!seenShortNames.add(shortName)) continue;

                    totalTests += tests;
                    totalFailures += failures;
                    totalErrors += errors;
                    totalSkipped += skipped;
                    totalTime += time;

                    Map<String, Object> suiteMap = new LinkedHashMap<>();
                    suiteMap.put("name", shortName);
                    suiteMap.put(K_TESTS, tests);
                    suiteMap.put(K_FAILURES, failures);
                    suiteMap.put(K_ERRORS, errors);
                    suiteMap.put(K_SKIPPED, skipped);
                    suiteMap.put("time", String.format("%.3fs", time));
                    suites.add(suiteMap);

                    // Parse individual test cases for slowest tests
                    NodeList testCases = doc.getElementsByTagName("testcase");
                    for (int k = 0; k < testCases.getLength(); k++) {
                        Element tc = (Element) testCases.item(k);
                        double tcTime = doubleAttr(tc, "time");
                        String tcName = tc.getAttribute("classname") + "." + tc.getAttribute("name");
                        allTestCases.add(new double[]{tcTime});
                        allTestCaseNames.add(tcName);
                    }
                } catch (Exception ignored) {
                    // skip malformed XML
                }
            }
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
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
        result.put("status", allPassed ? "PASSED" : "FAILED");
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
        } catch (IOException ignored) {
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
                    } catch (IOException ignored) {
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

    @SuppressWarnings("java:S3776") // parses JaCoCo CSV with per-package aggregation — inherently multi-branch
    private Map<String, Object> buildCoverageSection() {
        InputStream is = loadResource(CP_JACOCO, DEV_JACOCO);
        if (is == null) {
            return Map.of(K_AVAILABLE, false);
        }

        long instrCovered = 0, instrTotal = 0;
        long branchCovered = 0, branchTotal = 0;
        long lineCovered = 0, lineTotal = 0;
        long methodCovered = 0, methodTotal = 0;

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
                } catch (NumberFormatException ignored) {
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
        result.put("branches", counterMap(branchCovered, branchTotal));
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

    private Map<String, Object> buildGitSection() {
        try {
            // Fetch remote URL first so it can be shown as a link in the frontend.
            String remoteUrl = null;
            try {
                Process remoteProc = new ProcessBuilder("git", "remote", "get-url", "origin")
                        .directory(new File("."))
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(remoteProc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line = br.readLine();
                    if (line != null && !line.isBlank()) remoteUrl = line.trim();
                }
                remoteProc.waitFor();
            } catch (Exception ignored) { /* remote URL is optional */ }

            Process proc = new ProcessBuilder("git", "log", "--no-merges", "-15",
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

    private Map<String, Object> buildDependenciesSection() {
        InputStream is = loadResource(CP_POM, "pom.xml");
        if (is == null) return Map.of(K_AVAILABLE, false);

        List<Map<String,Object>> deps = new ArrayList<>();
        try (is) {
            DocumentBuilder db = secureNamespaceAwareDocumentBuilder();
            Document doc = db.parse(is);
            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element dep = (Element) depNodes.item(i);
                // Only direct dependencies (parent is <dependencies>, not <dependencyManagement>)
                if (!"dependencies".equals(dep.getParentNode().getNodeName())) continue;
                String groupId    = getTagText(dep, "groupId");
                String artifactId = getTagText(dep, "artifactId");
                String version    = getTagText(dep, "version");
                String scope      = getTagText(dep, "scope");
                if (scope.isEmpty()) scope = "compile";
                Map<String,Object> d = new LinkedHashMap<>();
                d.put("groupId", groupId);
                d.put("artifactId", artifactId);
                d.put(K_VERSION, version.isEmpty() ? "(managed)" : version);
                d.put("scope", scope);
                deps.add(d);
            }
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, K_ERROR, e.getMessage());
        }
        Map<String,Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE, true);
        r.put(K_TOTAL, deps.size());
        r.put(K_DEPENDENCIES, deps);
        return r;
    }

    private String getTagText(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() > 0 && nl.item(0).getParentNode() == parent) {
            return nl.item(0).getTextContent().trim();
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // Metrics section
    // -------------------------------------------------------------------------

    private Map<String, Object> buildMetricsSection() {
        InputStream is = loadResource(CP_JACOCO, DEV_JACOCO);
        if (is == null) return Map.of(K_AVAILABLE, false);

        long totalClasses = 0, totalMethods = 0, totalLines = 0, totalComplexity = 0;
        Map<String, long[]> pkgMetrics = new LinkedHashMap<>(); // [classes, lines, methods, complexity]

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

                    String pkg = cols[1].trim();
                    String[] parts = pkg.replace('/', '.').split("\\.");
                    String pkgShort = parts[parts.length - 1];
                    pkgMetrics.merge(pkgShort, new long[]{1, lines, methods, complexity},
                        (a, b) -> new long[]{a[0]+1, a[1]+b[1], a[2]+b[2], a[3]+b[3]});
                } catch (NumberFormatException ignored) {}
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

        Map<String,Object> r = new LinkedHashMap<>();
        r.put(K_AVAILABLE, true);
        r.put("totalClasses", totalClasses);
        r.put("totalMethods", totalMethods);
        r.put("totalLines", totalLines);
        r.put("totalComplexity", totalComplexity);
        r.put("packages", packages);
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
            .map(e -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("rule", e.getKey()); m.put("count", e.getValue()); return m; })
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
            .map(e -> { Map<String, Object> m = new LinkedHashMap<>(); m.put("checker", e.getKey()); m.put("count", e.getValue()); return m; })
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
            JsonNode dependencies = root.path("dependencies");
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

        int total = 0, killed = 0, survived = 0, noCoverage = 0;
        Map<String, Integer> byMutator  = new LinkedHashMap<>();
        Map<String, Integer> byStatus   = new LinkedHashMap<>();
        List<Map<String, Object>> surviving = new ArrayList<>();

        try (is) {
            DocumentBuilder db = secureDocumentBuilder();
            Document doc = db.parse(is);

            NodeList mutations = doc.getElementsByTagName("mutation");
            for (int i = 0; i < mutations.getLength(); i++) {
                Element m = (Element) mutations.item(i);
                String status  = m.getAttribute("status");
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

        } catch (Exception e) {
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
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleOrNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return round1(Double.parseDouble(v.trim())); } catch (NumberFormatException e) { return null; }
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
            } catch (IOException ignored) {
                // fall through
            }
        }
        File devFile = new File(devFallback);
        if (devFile.exists()) {
            try {
                return new java.io.FileInputStream(devFile);
            } catch (IOException ignored) {
                // fall through
            }
        }
        return null;
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
    @SuppressWarnings("java:S3776") // multi-step text cleaning with several branches — extracting further would obscure intent
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
        } catch (Exception e) {
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
            return Map.of(K_AVAILABLE, false, "reason", "GITLAB_PROJECT_ID not configured");
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
                entry.put("status",    p.path("status").asText("-"));
                entry.put("createdAt", p.path("created_at").asText("-"));
                // Duration — only available after pipeline completes
                JsonNode startedAt  = p.path("started_at");
                JsonNode finishedAt = p.path("finished_at");
                if (!startedAt.isNull() && !finishedAt.isNull()
                        && !startedAt.isMissingNode() && !finishedAt.isMissingNode()) {
                    try {
                        Instant start  = Instant.parse(startedAt.asText());
                        Instant finish = Instant.parse(finishedAt.asText());
                        entry.put("durationSeconds", Duration.between(start, finish).getSeconds());
                    } catch (Exception ignored) {
                        // Malformed timestamp — omit duration rather than break the section
                    }
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
    private Map<String, Object> buildBranchesSection() {
        try {
            // --sort=-committerdate: most recently updated branches first
            // %(refname:short): strips "origin/" prefix cleanly
            Process proc = new ProcessBuilder(
                    "git", "for-each-ref", "refs/remotes",
                    "--sort=-committerdate",
                    "--format=%(refname:short)|%(committerdate:iso)|%(authorname)",
                    "--count=20"
            ).redirectErrorStream(true).start();

            String output = new String(proc.getInputStream().readAllBytes()).trim();
            proc.waitFor();

            if (output.isBlank()) {
                return Map.of(K_AVAILABLE, false, "reason", "No remote branches found (git unavailable or no remotes)");
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
            result.put("branches", branches);
            result.put("total", branches.size());
            return result;
        } catch (Exception e) {
            return Map.of(K_AVAILABLE, false, "reason", "git error: " + e.getMessage());
        }
    }
}
