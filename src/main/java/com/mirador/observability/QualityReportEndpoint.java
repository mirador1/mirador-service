package com.mirador.observability;

import com.mirador.observability.quality.providers.ApiSectionProvider;
import com.mirador.observability.quality.providers.BuildInfoSectionProvider;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.lang.management.ManagementFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

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
 *
 * <p>Build-time (pre-aggregated in {@code META-INF/quality-build-report.json}
 * by {@link com.mirador.observability.quality.QualityReportGenerator} —
 * ADR-0052 Phase Q-2 / Q-2b):
 * <ul>
 *   <li><b>tests</b>        — Surefire XML: test counts, failures, slowest tests</li>
 *   <li><b>coverage</b>     — JaCoCo CSV: line/branch coverage %, per-class table</li>
 *   <li><b>bugs</b>         — SpotBugs XML: bug count, rank, category breakdown</li>
 *   <li><b>pmd</b>          — PMD XML: rule violation count and category breakdown</li>
 *   <li><b>checkstyle</b>   — Checkstyle XML: violation count by severity</li>
 *   <li><b>owasp</b>        — OWASP Dependency-Check JSON: CVE list with CVSS scores</li>
 *   <li><b>pitest</b>       — PIT XML: mutation test strength %</li>
 *   <li><b>dependencies</b> — pom.xml walk + Maven Central freshness</li>
 *   <li><b>licenses</b>     — THIRD-PARTY.txt: license summary + incompatible flag</li>
 *   <li><b>metrics</b>      — JaCoCo CSV: per-package complexity + top-10 classes + untested set</li>
 * </ul>
 *
 * <p>Runtime (live JVM / Spring / git state):
 * <ul>
 *   <li><b>build</b>        — build-info.properties: version, artifact, build time</li>
 *   <li><b>git</b>          — git log: last 15 commits, remote URL</li>
 *   <li><b>api</b>          — Spring MVC handler mappings: endpoint count, method breakdown</li>
 *   <li><b>runtime</b>      — JVM: uptime, active profiles, startup duration, JAR layer list</li>
 *   <li><b>branches</b>     — git for-each-ref: 20 most recently active remote branches</li>
 * </ul>
 *
 * <p>{@code sonar} + {@code pipeline} sections removed 2026-04-22 per
 * ADR-0052 Phase Q-1 — UI links out to sonarcloud.io + gitlab.com directly.
 */
@Component
@Endpoint(id = "quality")
public class QualityReportEndpoint {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Classpath / filesystem input paths moved to the individual parser /
    // provider classes under com.mirador.observability.quality.* — after
    // ADR-0052 Phase Q-2b, the endpoint no longer reads any tool output
    // directly (everything flows through META-INF/quality-build-report.json).

    // ── Map key constants — used across runtime section builders ──────────────
    // (Dead-code cleanup 2026-04-22 per Q-2b: 17 constants removed when their
    // call-sites moved to build-time parsers. Only the 9 still-referenced keys
    // stay here — adding new ones is fine, but favour inlining a one-shot
    // literal if it's only used in a single section.)
    private static final String K_AVAILABLE    = "available";
    private static final String K_ERROR        = "error";
    private static final String K_TOTAL        = "total";
    private static final String K_MESSAGE      = "message";
    private static final String K_TESTS        = "tests";
    private static final String K_COVERAGE     = "coverage";
    private static final String K_DEPENDENCIES = "dependencies";
    private static final String K_BRANCHES     = "branches";
    private static final String K_REASON       = "reason";

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

    // Sonar + GitLab pipeline REST integrations removed 2026-04-22 per ADR-0052.
    // The backend no longer makes outbound HTTPS calls to sonarcloud.io /
    // gitlab.com at /actuator/quality request time. UI dashboard links out
    // to those services directly. SONAR_TOKEN + GITLAB_API_TOKEN env vars
    // are no longer needed on the prod JVM.

    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final Environment environment;
    private final StartupTimeTracker startupTimeTracker;
    /**
     * Runtime section providers — kept as Spring beans because they need
     * live JVM / framework state (Environment, RequestMappingHandlerMapping).
     * Build-time sections (tests, coverage, bugs, pmd, checkstyle, owasp,
     * pitest, dependencies, licenses) no longer need injection — they come
     * from the pre-generated classpath JSON under ADR-0052 Phase Q-2.
     */
    private final BuildInfoSectionProvider buildInfoSectionProvider;
    private final ApiSectionProvider apiSectionProvider;

    public QualityReportEndpoint(RequestMappingHandlerMapping requestMappingHandlerMapping,
                                 Environment environment,
                                 StartupTimeTracker startupTimeTracker,
                                 BuildInfoSectionProvider buildInfoSectionProvider,
                                 ApiSectionProvider apiSectionProvider) {
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
        this.environment = environment;
        this.startupTimeTracker = startupTimeTracker;
        this.buildInfoSectionProvider = buildInfoSectionProvider;
        this.apiSectionProvider = apiSectionProvider;
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

        // Build-time sections — pre-generated at mvn prepare-package by
        // QualityReportGenerator (see ADR-0052 Phase Q-2). The backend no
        // longer parses XML/CSV tool outputs at HTTP request time; it just
        // reads one opaque classpath resource.
        // Sections covered: tests, coverage, bugs, pmd, checkstyle, owasp,
        //                   pitest, dependencies, licenses, metrics.
        Map<String, Object> buildTime = loadBuildTimeReport();
        for (String key : BUILD_TIME_KEYS) {
            Object val = buildTime.get(key);
            result.put(key, val != null ? val : Map.of(K_AVAILABLE, false,
                    K_REASON, "build-time report not generated yet (run `mvn prepare-package`)"));
        }

        // `sonar` + `pipeline` removed 2026-04-22 per ADR-0052 — the UI
        // dashboard links out to sonarcloud.io + gitlab.com directly.

        // Runtime sections — genuine JVM / Spring / local-git state.
        result.put("build",        buildInfoSectionProvider.parse());
        result.put("git",          buildGitSection());
        result.put("api",          apiSectionProvider.parse());
        result.put("runtime",      buildRuntimeSection());
        result.put(K_BRANCHES,     buildBranchesSection());
        return result;
    }

    /** The 10 file-based section keys that live in the pre-generated JSON. */
    private static final List<String> BUILD_TIME_KEYS = List.of(
            K_TESTS, K_COVERAGE, "bugs", "pmd", "checkstyle", "owasp",
            "pitest", K_DEPENDENCIES, "licenses", "metrics");

    private static final String QUALITY_BUILD_REPORT = "META-INF/quality-build-report.json";

    /**
     * Loads the build-time quality report written by {@link
     * com.mirador.observability.quality.QualityReportGenerator} during
     * {@code mvn prepare-package}. Returns an empty map when the resource
     * is absent (e.g. running from IDE without a full Maven build) — the
     * caller fills each missing section with {@code {available: false}}.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> loadBuildTimeReport() {
        ClassPathResource res = new ClassPathResource(QUALITY_BUILD_REPORT);
        if (!res.exists()) return Map.of();
        try (InputStream is = res.getInputStream()) {
            return (Map<String, Object>) BUILD_TIME_MAPPER.readValue(is, Map.class);
        } catch (Exception _) {
            return Map.of();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper BUILD_TIME_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    // Tests / coverage / bugs / pmd / checkstyle / owasp / pitest / deps /
    // licenses sections moved to build-time per ADR-0052 Phase Q-2 —
    // loaded via loadBuildTimeReport() from META-INF/quality-build-report.json
    // generated at mvn prepare-package by QualityReportGenerator.

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

    // API / Dependencies / Licenses delegates removed — the first is inlined
    // at the report() call site via apiSectionProvider.parse(); the others
    // ship from build-time JSON (Phase Q-2).

    // -------------------------------------------------------------------------
    // Licenses section
    // -------------------------------------------------------------------------

    // Licenses section moved to build-time per ADR-0052 Phase Q-2.

    // Metrics section moved to build-time per ADR-0052 Phase Q-2b — the
    // JaCoCo CSV was the last runtime tool-output re-read; it now ships
    // pre-aggregated in META-INF/quality-build-report.json via
    // MetricsSectionProvider.

    // PMD / Checkstyle / OWASP / Pitest sections moved to build-time per
    // ADR-0052 Phase Q-2. Loaded at runtime from META-INF/quality-build-report.json.

    // `buildSonarSection` + `ratingLabel` removed 2026-04-22 per ADR-0052 —
    // the SonarCloud REST call moved out of the runtime path. UI dashboard
    // links to https://sonarcloud.io/project/overview?id=Mirador_mirador-service
    // directly. CI-time fetches (via sonar-maven-plugin + sonarcloud.yml
    // workflow) remain the source of truth for Sonar state.

    // ─── Helpers extracted to com.mirador.observability.quality.parsers.ReportParsers ─
    //     (Phase B-1 split, 2026-04-22 — parseIntOrNull, parseDoubleOrNull, round1,
    //      intAttr, doubleAttr, parseDurationSeconds, secureDocumentBuilder,
    //      secureNamespaceAwareDocumentBuilder, loadResource live there now.
    //      Tests-section XML parsing moved to SurefireReportParser in the same package.)

    // cleanCveId + cleanCveDescription moved to OwaspReportParser (Phase B-1 split).

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

    // secureDocumentBuilder + secureNamespaceAwareDocumentBuilder moved to
    // com.mirador.observability.quality.parsers.ReportParsers (Phase B-1 split).

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

    // `buildPipelineSection` removed 2026-04-22 per ADR-0052 — the GitLab
    // REST call moved out of the runtime path. UI dashboard links to
    // https://gitlab.com/mirador1/mirador-service/-/pipelines directly.
    // GITLAB_PROJECT_ID + GITLAB_API_TOKEN are no longer needed on the
    // prod JVM.

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
