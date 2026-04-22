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

### B-3 — `bin/dev/stability-check.sh` 1457 → sections/* + driver [DONE 2026-04-22]

Shipped in MR !138. 30 section_* functions moved into `bin/dev/sections/`:
preflight.sh, ci.sh, code.sh, docs.sh, adr.sh, infra.sh, security.sh,
perf.sh, manual.sh, delta.sh (10 files). Driver shrunk 1457 → 272 LOC.
All 10 section files under the 400 LOC "container smell" threshold.
Zero behaviour change (same main() orchestration, same section names).

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

### B-2 — `.gitlab-ci.yml` svc 2619 → 9 includes (~3 h, fresh session)

Split into `ci/includes/{lint,test,security,k8s,quality,package,native,
deploy,release}.yml` + a thin orchestrator. **Validate via `glab ci config
compile` diff before/after** to ensure no job lost or duplicated.

**Scope notes** (2026-04-22 scan):
- ~35-40 top-level jobs in `.gitlab-ci.yml`, mostly self-contained.
- Only 1 YAML anchor block (`.compat-job:` + 4 `<<: *compat-job` uses)
  — all within the same file; no cross-file anchor risk.
- `extends:` work across includes natively; `rules:`/`default:`
  inheritance is preserved.

**Mapping plan**:
| Include file | Jobs |
|---|---|
| `ci/includes/lint.yml` | hadolint, openapi-lint, renovate-lint, promtool-check-rules |
| `ci/includes/test.yml` | unit-test, integration-test, integration-test:keycloak, mutation-test |
| `ci/includes/security.yml` | sast, dependency_scanning, owasp-dependency-check, secret-scan, trivy:scan, grype:scan, dockle, cosign:sign, cosign:verify, sbom:syft, semgrep |
| `ci/includes/k8s.yml` | test:k8s-apply, test:k8s-apply-prom, smoke-test |
| `ci/includes/quality.yml` | sonar-analysis, code-quality, generate-reports |
| `ci/includes/package.yml` | build-jar, docker-build |
| `ci/includes/native.yml` | build-native |
| `ci/includes/deploy.yml` | terraform-plan, terraform-apply, deploy:eks, deploy:aks, deploy:fly, (gke/k8s variants) |
| `ci/includes/release.yml` | release-please, pages |

Main `.gitlab-ci.yml` keeps: workflow, stages, default, variables,
cache, `.compat-job` anchor block + 4 compat jobs (they share the
anchor — keep together), `include:` directives for the 9 files.
Target: main file ≈ 300 LOC (variables + anchor + includes + small
compat block).

Recommended approach for a fresh session:
1. Capture baseline: `glab ci config compile > /tmp/ci-before.yml`
2. Extract one include file at a time (smallest first: release.yml,
   then native.yml).
3. After each extraction, `glab ci config compile > /tmp/ci-after.yml`
   + `diff /tmp/ci-before.yml /tmp/ci-after.yml` → should be identical
   order-of-jobs and identical per-job content. If differences,
   investigate before continuing.
4. Repeat for each category.
5. Final: validate full pipeline runs on a test MR before merging.

### B-4 — `.gitlab-ci.yml` UI 1067 → 6 includes (~2 h)

Same pattern as B-2. Files: validate, test, build, e2e, quality, security.

### B-5 — `quality.component.html` 1742 → `QualityPanel*` children (~4 h)

10+ panels (coverage, SpotBugs, Pitest, OWASP, PMD, Checkstyle, Sonar,
test results, …) → 1 child component per panel + parent template ~150 lines.
Largest UI refactor; touch only when fresh. **Pattern**: 1 panel = 1 file
(see ~/.claude/CLAUDE.md → "File length hygiene" rule #6).

### B-6 — `dashboard.component` (.ts 1022 + .scss 1258) → 1 widget/file (~4 h)

Split by widget: ArchitectureMap, HealthProbes, ErrorTimeline, BundleTreemap,
CodeQualitySummary, DockerControls, etc. **Pattern confirmed 2026-04-22** — 1
widget = 1 file (see `~/.claude/CLAUDE.md` → "File length hygiene" rule #6
+ UI `CLAUDE.md` → "1 widget / 1 panel = 1 file").

### B-7 — seconde passe: 10 fichiers additionnels (~12 h)

Scan 2026-04-22 identifie 10 offenders ≥ 700 LOC hors Phase B-1..6 :

| # | Fichier | LOC | Pattern |
|---|---|---|---|
| 1 | `ui/quality.component.scss`    | 1206 | 1 panel SCSS/file (pair avec B-5) |
| 2 | `ui/customers.component.ts`    |  904 | 1 tab/file (list, CRUD, import/export, bio, todos, enrich) |
| 3 | `ui/customers.component.scss`  |  820 | pair avec ↑ |
| 4 | `ui/security.component.scss`   |  828 | 1 demo/file |
| 5 | `ui/about.component.scss`      |  813 | section-scoped SCSS |
| 6 | `ui/quality.component.ts`      |  806 | presque thin après B-5 children |
| 7 | `svc/CustomerController.java`  |  782 | `CustomerReadController` + `CustomerWriteController` + `CustomerExportController` |
| 8 | `svc/run.sh`                   |  763 | 29 cases → `bin/run/cases/<case>.sh` + thin dispatcher (1 case = 1 file) |
| 9 | `ui/diagnostic.component.ts`   |  711 | panels inside — 1 diag/file |
| 10 | `ui/settings.component.scss`  |  688 | just under 700 — watch for drift |

Ordre proposé (gain/risque ↑): **B-7-8 run.sh (fastest, biggest relative gain),
B-7-7 CustomerController, B-7-5/4/3/2 SCSS batches, B-7-1/6 quality component
parts after B-5, B-7-9 diagnostic**.

### B-1b (follow-up) — non-parser sections of QualityReportEndpoint

After B-1, the endpoint will be ~1300 lines (still > 1000). Second pass
extracts build-info/git/api/deps/metrics/sonar/pipeline/branches/runtime
into `com.mirador.observability.quality.providers`. Brings the endpoint
under ~250 lines (true thin aggregator).

**Started 2026-04-22**:

- ✅ `BuildInfoSectionProvider` — reads META-INF/build-info.properties.
- ✅ `ApiSectionProvider` — walks RequestMappingHandlerMapping.
- Endpoint 1218 → 1179 LOC (−39).

**Remaining 5 providers** (priority order — smallest self-contained first):

- `GitSectionProvider` (+ `fetchGitRemoteUrl` helper, needs `GIT_BIN` static)
- `RuntimeSectionProvider` (+ `buildJarLayersSection`, needs Environment + StartupTimeTracker)
- `BranchesSectionProvider` (git for-each-ref, needs GIT_BIN)
- `LicensesSectionProvider` (reads THIRD-PARTY.txt)
- `MetricsSectionProvider` (walks Micrometer registry)
- `DependenciesSectionProvider` (+ `parseDependencyAnalysis`, reads pom.xml + dependency-tree.txt)

~~`PipelineSectionProvider` + `SonarSectionProvider`~~ — dropped 2026-04-22
under ADR-0052 / Phase Q-1. Backend no longer proxies GitLab or
SonarCloud REST; UI links out directly.

Target: endpoint ≈ 250 LOC (true thin aggregator).

---

## 🚧 Phase Q — backend decoupling from build/quality tools (ADR-0052)

### Q-1 — remove sonar + gitlab REST from backend [DONE 2026-04-22]

Removed `buildSonarSection` + `buildPipelineSection` + 6 `@Value`
injections. Backend no longer makes outbound HTTPS to sonarcloud.io
or gitlab.com at `/actuator/quality` request time. UI dashboard
links out instead. Endpoint 1179 → 1004 LOC.

### Q-2 — move file-based parsers to build-time JSON [DONE 2026-04-22]

Shipped in MR !138. `QualityReportGenerator` CLI runs at `mvn
prepare-package` via `exec-maven-plugin`, writes
`target/classes/META-INF/quality-build-report.json`. Endpoint loads
the JSON instead of parsing tool outputs at runtime.

Cumulative impact: `QualityReportEndpoint` 1934 → 646 LOC (−66 %).
Backend runtime no longer depends on Jackson / javax.xml.parsers /
HttpClient on the `/actuator/quality` hot path.

**Q-2b follow-up (remaining runtime file-reader)** — `buildMetricsSection`
still reads JaCoCo CSV at runtime (~120 LOC). Moving it to build-time
would fully close ADR-0052's "no tool parsing at runtime" intent.
Estimated ~45 min.

### Q-3 — (optional, open) if dashboard ever needs Sonar/GitLab data inline

Revisit via build-time cached JSON injected by CI, NOT a runtime
REST call. New ADR rather than reopen ADR-0052.

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

## 🧭 Ideas pour plus tard (scope à confirmer)

### 🔥 Release automation — tool swap (discovered 2026-04-22, urgent-ish)

`googleapis/release-please` configured + wired in `.gitlab-ci.yml` on
both repos — but the tool is **GitHub-API-only**. With a GitLab PAT +
`--repo-url https://gitlab.com/...`, release-please still hits
`api.github.com/graphql` for its "existing releases" query → 401 Bad
Credentials. Evidence: svc pipeline #660 release-please job.

**Current state** (2026-04-22 15:00):
- release-please job DISABLED (`when: never`) on both repos — stops
  red-firing every main merge.
- `RELEASE_PLEASE_TOKEN` CI vars still provisioned (harmless; rotate
  or delete when the replacement ships).
- `config/release-please-config.json` + `.release-please-manifest.json`
  still present; format is portable to other tools.
- [`docs/how-to/activate-release-please.md`](how-to/activate-release-please.md)
  has a 🔴 DISABLED banner.
- `CHANGELOG.md` stays hand-rolled (always was the fallback).

**Swap candidates**: `semantic-release` + `@semantic-release/gitlab`
(industry standard, GitLab-native, ~3 h); `standard-version`
(minimal, ~2 h); hand-rolled CHANGELOG status quo (0 h, no auto
vX.Y.Z tags).

**Recommendation**: `standard-version` — simplest working path.
Defer `semantic-release` unless the team grows past 2 contributors.



### GitLab Observability integration (potentiel 2e OTLP exporter)

User signalait 2026-04-22 l'URL <https://gitlab.com/groups/mirador1/-/observability/setup>
— GitLab a une feature Observability intégrée (OTLP ingest avec auth
group-level, cf. [docs.gitlab.com/ee/operations/tracing.html](https://docs.gitlab.com/ee/operations/tracing.html)).

**Ce que ça apporterait** : telemetry visualisable DIRECTEMENT dans
l'UI GitLab (pas besoin de démarrer Grafana/Tempo localement pour un
reviewer), + conservation cross-session sans coûter de VM GCP.

**Questions à trancher avant d'implémenter** :
- Disponibilité sur free tier ? (`plan: None` sur le groupe = free
  tier probablement ; API `observability_config` retourne 404).
- Format d'auth (Bearer token group-level ? deploy token ?).
- Dual-export depuis l'OTel Collector (actuel → LGTM local + GitLab)
  ou exporter directement depuis Spring Boot ?
- Cohabitation avec ADR-0010 "OTLP push to Collector, not Prometheus
  scrape" — OK en complément, pas en remplacement.

**Estimation rough** : si faisable sur free tier, ~3-4 h : OTel
Collector config (pipeline dual), env vars GitLab token, ADR
documentant le flow.

**Prérequis user** : valider la dispo free tier en ouvrant
<https://gitlab.com/groups/mirador1/-/observability/setup> et
reportant ce qui est proposé (tokens, endpoints, plan-lock).

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
