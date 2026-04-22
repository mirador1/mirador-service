# TASKS — pending work backlog

Source of truth across Claude sessions. Read this first. Update when
adding/starting/finishing a task. Delete when empty (per CLAUDE.md).

---

## ✅ Recently shipped — refer to git log for full history

Last meaningful checkpoints:

- **`stable-v1.0.10`** (2026-04-22) — Phase A quality enforcement layer
  - Audit doc `docs/audit/quality-thresholds-2026-04-21.md` (40+ rules)
  - `section_file_length` in stability-check.sh (≥ 1500 BLOCK)
  - PMD industry thresholds (NcssCount class 1500→750, etc.)
  - Custom Checkstyle config replacing google_checks (FileLength 1000,
    MethodLength 100, LineLength 120, ParameterNumber 7)
  - UI ESLint size + complexity rules at WARN
  - UI workflow path filter — eslint.config.mjs + .gitleaks.toml + .prettierrc
- **`stable-v1.0.9`** (2026-04-21) — Phase 2 + Phase 3 (alerts + load + 2 demos)
  - Phase 2: cosign re-verify, exemplars Grafana→Tempo, OpenAPI→TS types,
    axe-core a11y, jqwik property-based, Hurl smoke, guided tour, ADR graph
  - Phase 3: PrometheusRule + 6 runbooks + ADR-0048 + promtool CI,
    k6 load.js + nightly schedule, /find-the-bug, /incident-anatomy
- **`stable-v1.0.6` to `v1.0.8`** (2026-04-21) — CI hygiene wave (sonar
  scope-out, mermaid lint, ADR proposed-aging gate, lighthouse abs thresholds)

For commit-level granularity: `git log --oneline stable-v1.0.6..stable-v1.0.10`.

---

## 🔧 Phase B — file splits to clear the new ≥1500 line gate (~17h, sessions dédiées)

Order picked by gain/risk ratio (lowest risk first inside each tier).

### B-3 — `bin/dev/stability-check.sh` 1457 → sections/* + driver [~2 h, easy]

30 sections currently in one file. Split into thematic groups (preflight,
ci, code, docs, adr, infra, manual, delta) + a thin driver. Bash refactor,
no behaviour change.

### B-1 — `QualityReportEndpoint.java` 1934 → 7 parsers + thin aggregator [~2 h]

Extract per-tool parsers (Surefire, Jacoco, SpotBugs, PMD, Checkstyle,
OWASP, Pitest) into `com.mirador.observability.quality.parsers`. Endpoint
becomes a thin aggregator wired via Spring `List<QualitySection>`. Keeps
the 9 non-parser sections (build-info, git, api, deps, metrics, sonar,
pipeline, branches, runtime) inline for B-1b follow-up.

**Completed 2026-04-22** — 7 parsers + shared helpers now live in
`com.mirador.observability.quality.parsers`:

- ✅ `ReportParsers` (static utility: parseIntOrNull, parseDoubleOrNull,
  round1, intAttr, doubleAttr, parseDurationSeconds, getTagText,
  secureDocumentBuilder, secureNamespaceAwareDocumentBuilder, loadResource).
- ✅ `SurefireReportParser` (buildTestsSection + ParsedSuite + parseOneSuite).
- ✅ `JacocoReportParser` (buildCoverageSection + counterMap).
- ✅ `SpotBugsReportParser` (buildBugsSection + priorityLabel).
- ✅ `PmdReportParser` (buildPmdSection + priorityLabel).
- ✅ `CheckstyleReportParser` (buildCheckstyleSection).
- ✅ `OwaspReportParser` (buildOwaspSection + cleanCveId + cleanCveDescription).
- ✅ `PitestReportParser` (buildPitestSection).

Endpoint shrunk 1934 → 1218 LOC (−716, −37%). Below 1500 BLOCK
threshold. 9 non-parser sections remain inline — see B-1b below.

All XXE-hardened DocumentBuilder factories preserved; test suite
(QualityReportHelpersTest 18/18) green.

### B-2 — `.gitlab-ci.yml` svc 2619 → 9 includes (~3 h)

Split into `ci/includes/{lint,test,security,k8s,quality,package,native,
deploy,release}.yml` + a thin orchestrator. **Validate via `glab ci config`
diff before/after** to ensure no job lost or duplicated.

### B-4 — `.gitlab-ci.yml` UI 1067 → 6 includes (~2 h)

Same pattern as B-2. Files: validate, test, build, e2e, quality, security.

### B-5 — `quality.component.html` 1742 → `QualityPanel*` children (~4 h)

10+ panels (coverage, SpotBugs, Pitest, OWASP, PMD, Checkstyle, Sonar,
test results, …) → 1 child component per panel + parent template ~150 lines.
Largest UI refactor; touch only when fresh.

### B-6 — `dashboard.component` (.ts 1022 + .scss 1258) → 1 widget/file (~4 h)

Split by widget: ArchitectureMap, HealthProbes, ErrorTimeline, BundleTreemap,
CodeQualitySummary, DockerControls, etc.

### B-1b (follow-up) — non-parser sections of QualityReportEndpoint

After B-1, the endpoint will be ~1300 lines (still > 1000). Second pass
extracts build-info/git/api/deps/metrics/sonar/pipeline/branches/runtime
into `com.mirador.observability.quality.providers`. Brings the endpoint
under ~250 lines (true thin aggregator).

**Started 2026-04-22**:

- ✅ `BuildInfoSectionProvider` — reads META-INF/build-info.properties.
- ✅ `ApiSectionProvider` — walks RequestMappingHandlerMapping.
- Endpoint 1218 → 1179 LOC (−39).

**Remaining 7 providers** (priority order — smallest self-contained first):

- `GitSectionProvider` (+ `fetchGitRemoteUrl` helper, needs `GIT_BIN` static)
- `RuntimeSectionProvider` (+ `buildJarLayersSection`, needs Environment + StartupTimeTracker)
- `PipelineSectionProvider` (GitLab API call, needs HttpClient + @Value config)
- `BranchesSectionProvider` (git for-each-ref, needs GIT_BIN)
- `LicensesSectionProvider` (reads THIRD-PARTY.txt)
- `SonarSectionProvider` (SonarCloud REST call, needs @Value config)
- `MetricsSectionProvider` (walks Micrometer registry)
- `DependenciesSectionProvider` (+ `parseDependencyAnalysis`, reads pom.xml + dependency-tree.txt)

Target: endpoint ≈ 250 LOC (true thin aggregator).

---

## 🚦 Phase C — flip enforcement (~30 min, post-Phase-B)

Once Phase B brings the current outliers below the new thresholds:

- `pom.xml` : `failOnViolation=true` on PMD + Checkstyle
- `pom.xml` : `<skip>false</skip>` on PMD + Checkstyle plugins (currently
  activated only via `-Preport` profile — flip to default)
- `eslint.config.mjs` : `warn` → `error` on the 6 size/complexity rules
- Suppression baseline files (created during B if needed) → delete

After Phase C: every new violation fails the MR. Tag `stable-v1.0.11`.

---

## 👤 Actions user

### ✅ DONE 2026-04-22 — Docker VM 16 GB + shields retired

Docker Desktop VM raised from default 7.6 GB → **16 GB** (`docker system
info` confirms 15.6 GiB + 10 CPUs). k8s-apply + k8s-apply-prom shields
retired in commit 314012f — both jobs now BLOCKING again on main.
See `docs/audit/session-2026-04-22-user-actions-closed.md` for the full
flow. Related: ADR-0049 "shield retirement log" section.

### ✅ DONE 2026-04-22 — Signed commits (S2)

Local SSH-sign configured via `bin/dev/setup-signed-commits.sh`
(ED25519 key, `commit.gpgsign=true` global). GitHub signing key
registered with `gh ssh-key add --type signing`. Commit 314012f was the
first signed commit — `git log --show-signature` returns `Good "git"
signature for benoit.besson@gmail.com`. `required_signatures` re-enabled
on both repos' main branches via `gh api -X POST
.../protection/required_signatures` (both return `"enabled": true`).

### TF_STATE_BUCKET — re-enable terraform-plan

Currently scoped-out via rules.if (was failing 5/5 with "bucket doesn't
exist"). Either provision the GCS bucket via `terraform/bootstrap.sh` and
remove the scope-out, OR drop terraform-plan from the pipeline entirely.
Provisioning a new GCS bucket costs money (Cloud Storage ~€0.02/GB/month
+ egress fees). Confirm with user before running the terraform apply.

---

## 🟡 Improvements (on a slow-day backlog)

### SonarCloud Quality Gate — remaining drivers

- **svc `new_coverage = 47.3%`** < 80% — gaps on `CustomerController` helpers
  + Auth0 JWT validation paths. Real test work; split per area in dedicated MRs.
- **svc + UI `new_security_hotspots_reviewed = 0%`** — manual UI step (mark
  as "safe" with justification) on https://sonarcloud.io
- **UI `new_coverage = 0%`** — same shape, real test work.

### grype:scan shield — find an arm64 path

`anchore/grype:v0.87.0-debug` is amd64-only → Go runtime panic on
macbook-local arm64. Options: bump to a newer multi-arch debug variant if
released, scope to schedule-only on SaaS amd64 runner, or drop the job
until grype ships arm64. Not safe to flip without one of these.

### ADR follow-ups from kube-prometheus-stack overlay (ADR-0039)

Tracked separately — see ADR-0039 for the full list. Highlights: GKE
Workload Identity for Prometheus → Cloud Monitoring federation, retention
sizing as data accumulates.

---

## 📋 Phase 4 — long-running cleanup (multi-session, ~15h+)

### B1 + F1 — RxJS subscribe-leak cleanup (UI)

86 `.subscribe()` calls without `takeUntilDestroyed` flagged at ESLint setup
(2026-04-21). Each is a potential leak when the component / service is
destroyed mid-stream. Per-feature cleanup; reuse the `takeUntilDestroyed`
pattern already in use in tour-overlay, find-the-bug, and the auth interceptor.

### A3 — Sonar coverage to >80% on core module (svc)

Currently ~47%. Hot zones: chaos package (recently added, low coverage),
auth (JWT + Auth0 paths), Flyway migrations (no tests).

---

## 🟢 Nice-to-have

### Re-enable Alertmanager when project moves beyond demo

ADR-0048 documents the deliberate "evaluate but don't route" decision for
Phase 3 O2 alerts. When (if) the project gains an on-call rotation or a
real production deployment, flip `alertmanager.enabled: true` in both
prom overlays + add a receiver config (Slack webhook minimum).

### ADR for "CI shields with dated exit ticket" pattern

Currently documented inline in `pom.xml` + `.gitlab-ci.yml` comments.
Promote to a real ADR-00XX so the pattern survives the comments getting
edited away.

### ADR for CI modularisation plan (pre-B-2 + B-4)

Document the include split + naming convention + glab ci config diff
gate before starting B-2/B-4. Saves hours of "wait, why did I split it
this way" on future sessions.
