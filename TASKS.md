# TASKS — pending work backlog

Source of truth across Claude sessions. Read this first. Update when
adding/starting/finishing a task. Delete when empty (per CLAUDE.md).

---

## 🌍 Phase OVH — OVH Cloud as 2nd canonical delivery target (decided 2026-04-23)

**Decision** : Promote OVH to a **canonical-tier deployment target alongside GCP** (NOT a "reference module" like Scaleway/AWS/Azure). Motivation : **French sovereignty** + **HDS certification** (health-data hosting — what Scaleway lacks). Scope **complet** : TF + CI + apply + observability overlay + cost tracking + lifecycle scripts.

This OVERRIDES the "OVH ≈ Scaleway, not scaffolded" position from [ADR-0036](docs/adr/0036-multi-cloud-terraform-posture.md) — that was a "reference modules only" decision; OVH joins GCP at the canonical tier with full delivery capability.

### Sub-tasks (in order)

- [ ] **OVH-1** ADR-0053 — Pose the decision : OVH alongside GCP, motivation souveraineté + HDS, scope complet → `docs/adr/0053-ovh-canonical-target.md`
- [ ] **OVH-2** Amend ADR-0036 footer ("Amended by ADR-0053") to flag the elevation
- [ ] **OVH-3** Terraform module `deploy/terraform/ovh/` — full canonical-grade module:
  - `main.tf` — provider + `ovh_cloud_project_kube` + `ovh_cloud_project_kube_nodepool`
  - `network.tf` — vRack + private network + node placement
  - `variables.tf` — region (GRA9, SBG5...), application_key/secret/consumer_key, project_id
  - `outputs.tf` — kubeconfig, cluster_id, node IPs
  - `README.md` — cost breakdown, prerequisites (ovh-cli login flow), apply/destroy commands, runbook
- [ ] **OVH-4** K8s overlay `deploy/kubernetes/overlays/ovh-prom/` — mirror `gke-prom/` with OVH-specific tweaks (LoadBalancer annotations, storage class, ingress controller)
- [ ] **OVH-5** Cost audit script `bin/budget/ovh-cost-audit.sh` — query OVH `/me/order` API; integrate into `bin/budget/budget.sh`
- [ ] **OVH-6** CI : add `terraform-plan-ovh` (auto on MR, default `terraform`) + `terraform-plan-ovh-tofu` (parallel, `TF_BIN=tofu` to prove dual-compat) + `terraform-apply-ovh` (`when: manual`) jobs in `.gitlab-ci/terraform.yml`. Per ADR-0053 tooling section : Terraform default, OpenTofu opt-in via `TF_BIN=tofu`.
- [ ] **OVH-7** Cluster lifecycle scripts : `bin/cluster/ovh-up.sh` + `bin/cluster/ovh-down.sh` analogous to `gcp-up.sh` / `gcp-down.sh`
- [ ] **OVH-8** README updates :
  - `deploy/terraform/README.md` — add OVH row + tier indicator
  - top-level `README.md` § "Multi-cloud delivery" — list OVH alongside GCP
  - `docs/architecture/deployment.md` — describe OVH topology
- [ ] **OVH-9** `.env.example` — add `OVH_APPLICATION_KEY` / `OVH_APPLICATION_SECRET` / `OVH_CONSUMER_KEY` / `OVH_PROJECT_ID` with comments pointing to `https://eu.api.ovh.com/createToken/`
- [ ] **OVH-10** Smoke test : `terraform init && terraform validate` in `deploy/terraform/ovh/` — must pass before bundling
- [ ] **OVH-11** Final MR : bundle all 10 above with self-contained description

### Limitations / handoff to user
- Apply requires OVH credentials (Mirador session has none) → user runs the first `terraform apply` themselves
- The CI `terraform-apply-ovh` job stays `when: manual` until first credentials are wired — clear comment in YAML

### Estimated effort
- Without credentials testing : 8-10 h (TF + CI + scripts + docs)
- Plus first-apply session with user credentials : +2-3 h

### Related ADRs (read first)
- [ADR-0030](docs/adr/0030-choose-gcp-as-the-kubernetes-target.md) — why GCP was the canonical pick
- [ADR-0036](docs/adr/0036-multi-cloud-terraform-posture.md) — what Phase OVH amends
- [ADR-0007](docs/adr/0007-workload-identity-federation.md) — auth pattern to mirror

---

## ✅ Recently shipped — refer to git log for full history

Last meaningful checkpoints:

- **`stable-v1.0.18`** (2026-04-22) — GKE demo cluster observability stack
  - `bin/cluster/demo/install-{observability,gitlab-agent,gmp-frontend}.sh`
    + 3 toggles in up.sh (WITH_PROMETHEUS / WITH_GITLAB_AGENT /
    WITH_GMP_FRONTEND). One-command demo-up brings cluster + Argo +
    kube-prom-stack (Autopilot-compat) + GitLab Agent + GMP query
    frontend bridging Cloud Monitoring to PromQL.
  - Grafana lgtm gets a 5th datasource "Prometheus (GMP — cluster
    metrics)" — cAdvisor + ksm + kubelet via the GMP frontend bridge.
  - `bin/cluster/port-forward/prod.sh` adds 2 tunnels (gmp-frontend
    29091, kube-prom 22090) in the upstream+20000 scheme.
  - `docs/ops/runbooks/gmp-frontend-openlens.md` runbook covering the
    Autopilot kube-system lockdown root cause + 6 alternatives evaluated
    + "what this unlocks" for 6 consumers.
  - `bin/ship/gitlab-release.sh` + `bin/admin/gitlab-housekeeping.sh`
    helper scripts for tag→Release promotion + sidebar feature cleanup.
  - `.gitlab-ci.yml` workflow:rules now allows `.gitlab/**` +
    `.gitlab-ci/**` paths (unblocks Agent config MRs, validates includes).
- **`stable-v1.0.17`** (2026-04-22) — Phase B-7-7 + B-7-8 (svc)
  - `run.sh` 763 → 46 LOC dispatcher + 31 `bin/run/<case>.sh`
    sub-scripts + 2 alias symlinks (`check → test`, `ci → verify`)
  - `CustomerDiagnosticsController` extracted (3 endpoints:
    `/stream`, `/slow-query`, `/export`); `CustomerController`
    shrinks 782 → 705 LOC
- **`stable-v1.0.16`** (2026-04-22) — Phase B-2 CI modularisation (svc)
  - `.gitlab-ci.yml` 2609 → 173 LOC + 9 includes under `.gitlab-ci/`
  - Dead duplicate `hadolint:` (floating `:latest` tag) removed
  - `.config/lefthook.yml` gitlab-ci-lint uses `--ref <branch> --dry-run`
    so local includes resolve against current branch state
  - ADR-0050 flipped Proposed → Accepted
- **`stable-v1.0.15`** (2026-04-22) — Q-2b (closes ADR-0052)
  + Phase B-4 CI modularisation (UI)
  - Q-2b: `MetricsSectionProvider` moves last runtime JaCoCo CSV read to
    build-time. `QualityReportEndpoint` 646 → 469 LOC (-76% since session
    start 1934). 17 dead K_ constants + 6 dead imports + 16 dead CP_/DEV_
    path constants cleaned up.
  - B-4: UI `.gitlab-ci.yml` 1086 → 144 LOC + 7 includes (lint, test,
    build, security, quality, deploy, release). `.install:` stays in
    parent (used via `extends:` by 10+ jobs across includes).
- **`stable-v1.0.10`** through **v1.0.14** — Phase A quality enforcement
  layer + Keycloak migration + release-please tool-mismatch discovery.
  See `git log --oneline stable-v1.0.10..stable-v1.0.16` for details.

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

### B-2 — `.gitlab-ci.yml` svc 2619 → 173 + 9 includes [DONE 2026-04-22]

Shipped in MR !142 (commits e7dccf8 + 7d3907a). Parent shrinks 2609 →
173 LOC (-93%). 9 local includes under `.gitlab-ci/`:
`lint.yml` (238), `test.yml` (302), `security.yml` (512), `quality.yml`
(316), `package.yml` (84), `native.yml` (75), `k8s.yml` (420),
`deploy.yml` (434), `release.yml` (103). See commit for the design notes
(anchor scoping, `!reference` cross-file tolerance, lefthook hook
update to use `--ref <branch> --dry-run`).

**Legacy plan below kept for reference**:

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

### B-4 — `.gitlab-ci.yml` UI 1086 → 144 + 7 includes [DONE 2026-04-22]

Shipped in MR !77 (mirador-ui). Parent shrinks 1086 → 144 LOC (-87%).
7 local includes: lint.yml (159), test.yml (217), build.yml (85),
security.yml (237), quality.yml (98), deploy.yml (143),
release.yml (48). `.install:` hidden job stays in the parent because
10+ jobs depend on it via `extends:` across includes.

### B-5 — `quality.component.html` 1708 → 298 + 9 children [DONE 2026-04-22]

Shipped in UI MR !80 (commits 6b8d573 + b9be2d0 + 16d59d2 + bba2474 +
68876dc + 9ebe237 + ceec449 + 3c4af18). Parent shrinks 1708 → 298 LOC
(-82%). Every `@if (selectedTab() === 'xxx')` block moved to a
standalone `<app-qt-xxx>` child:

- app-qt-pipeline (41) — ADR-0052 external-link card
- app-qt-branches (56) — git branches table
- app-qt-runtime (71) — JVM uptime + JAR layers
- app-qt-analysis (95) — SpotBugs
- app-qt-security (124) — OWASP CVEs
- app-qt-build (210) — build info + PMD + Checkstyle
- app-qt-tests (259) — Surefire + JaCoCo
- app-qt-overview (279) — at-a-glance cards + tabSelected output
- app-qt-mutation (514) — PITEST + git + api + deps + licenses + metrics

Plus `quality-helpers.ts` (84 LOC) with pure functions shared by all
tabs. `quality.component.ts` also shrinks 806 → 764 LOC after orphan
helper cleanup. Bug fix: `styleUrl: '../quality.component.scss'` added
to each child (view encapsulation scoping).

**Legacy plan below kept for reference**:

10+ panels (coverage, SpotBugs, Pitest, OWASP, PMD, Checkstyle, Sonar,
test results, …) → 1 child component per panel + parent template ~150 lines.
Largest UI refactor; touch only when fresh. **Pattern**: 1 panel = 1 file
(see ~/.claude/CLAUDE.md → "File length hygiene" rule #6).

### B-6 — `dashboard.component` split [DONE 2026-04-22, threshold-only]

Shipped in UI MR !89 (commits ec6d210 + cb246ca + 6fc04fc, tagged
[stable-v1.0.21](https://gitlab.com/mirador1/mirador-ui/-/tags/stable-v1.0.21)).
Pragmatic incremental scope — extract types + static topology data + Sass
partials. Brings the file under the 1000-LOC plan-split trigger; full
"1 widget per file" extraction (ArchitectureMap, HealthProbes,
ErrorTimeline, …) is still pending but no longer blocking Phase C.

| File | Before | After | How |
|---|---|---|---|
| `dashboard.component.ts` | 1022 | 713 | `dashboard-types.ts` (49 — SVC, ActuatorHealth, DockerContainer) + `dashboard-topology-data.ts` (315 — TopoNode, TopoEdge, DASHBOARD_TOPO_COLUMNS/NODES/EDGES static data) |
| `dashboard.component.scss` | 1291 | 59 | 8 Sass partials (page-chrome, charts, docker, architecture, data-grids, observability, metrics, quality-summary) |

The 6 topology helper methods (`refreshTopology` / `topoNodesInCol` /
`topoConnections` / `topoContainer` / `topoNodeColor` /
`topoStatusTooltip`) stay in-class because they reference component
state (signals + http). Future B-6b would extract them alongside a
true widget-per-file split.

**B-6 follow-up deferred** (B-6b) — the per-widget extraction
(ArchitectureMap, HealthProbes, ErrorTimeline, BundleTreemap,
CodeQualitySummary, DockerControls, ObservabilityLinks, LiveActivity)
is a 4 h fresh session; current 713-LOC component is below threshold
and refactor pressure is gone.

### B-7 — seconde passe: 10 fichiers additionnels (majorly DONE 2026-04-22)

Scan 2026-04-22 identifie 10 offenders ≥ 700 LOC hors Phase B-1..6 :

| # | Fichier | LOC avant | LOC après | Status |
|---|---|---|---|---|
| 1 | `ui/quality.component.scss`    | 1206 |   27 | **DONE** B-7-1 — split into 5 Sass partials (page-chrome / overview-cards / section-layout / tabs / panels) |
| 2 | `ui/customers.component.ts`    |  904 |  813 | **partial** B-7-2 — extract data (46 names) + helpers (uuid, randomCustomer) + types (DetailTab, SortField, SortDir) + spec (5 tests, 0 TestBed). Full by-concern split deferred (complex state) |
| 3 | `ui/customers.component.scss`  |  820 |   22 | **DONE** B-7-3 — split into 4 Sass partials (controls / forms / modal-detail / recent-banner) |
| 4 | `ui/security.component.scss`   |  862 |   52 | **DONE** B-7-4 — split into 5 Sass partials (page-chrome / common-ui / mechanisms / jwt-inspector / headers-audit) |
| 5 | `ui/about.component.scss`      |  813 |   22 | **DONE** B-7-5 — split into 4 Sass partials (tabs-header / sections / compat-pipeline / deploy) |
| 6 | `ui/quality.component.ts`      |  806 |  249 | **DONE** B-7-6 — 24 interfaces → `quality-types.ts` (527 LOC types-only); helpers in `quality-helpers.ts` (84 LOC) |
| 7 | `svc/CustomerController.java`  |  782 |  535 | **DONE** B-7-7 — 3-way split: CustomerDiagnosticsController (142) + CustomerEnrichmentController (185). Original shrank -32% |
| 8 | `svc/run.sh`                   |  763 |   46 | **DONE** B-7-8 — split into 31 `bin/run/<case>.sh` + dispatcher + 2 alias symlinks (MR !143) |
| 9 | `ui/diagnostic.component.ts`   |  711 |  628 | **partial** B-7-9 — 7 interfaces → `diagnostic-types.ts` (98 LOC). .scss split separately (616 → 19 + 5 partials: page-chrome / history / scenarios / charts / jobs-tests) |
| 10 | `ui/settings.component.scss`  |  688 |   27 | **DONE** B-7-10 — split into 5 Sass partials (page-chrome / card-config / loggers / sql-explorer / jobs-flags); dedup of duplicated logger/sql selectors tracked as follow-up |

**Wave summary (2026-04-22)**: 6 `.scss` + 2 `.ts` split deliveries landed
in UI !82 (shipped under 1 MR accumulating 6 commits). All files below
the 1000-LOC plan-split trigger except `ui/dashboard.component.scss` +
`ui/dashboard.component.ts` which remain scope of Phase B-6 (deferred).

**B-7-8 done details**: `run.sh` 763→46 dispatcher + `bin/run/_preamble.sh`
(84 shared helpers) + 31 case scripts (flat layout intentional — dispatcher
discovers scripts dynamically, `ls bin/run/` IS the UX).

**B-7-2 follow-up deferred** — the pure-code extract (data + helpers +
types + spec) was low-risk; the full by-concern component split (list /
CRUD / detail tabs) is higher risk because `CustomersComponent` shares
signals across all three concerns. Defer to a fresh session alongside
Phase B-6 or as an explicit B-7-2b wave.

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

### Q-2b — MetricsSectionProvider (closes ADR-0052) [DONE 2026-04-22]

Shipped in MR !141. `MetricsSectionProvider` extracted (171 LOC);
JaCoCo CSV aggregation (per-package complexity, top-10 classes,
untested set) moves to build-time. `QualityReportEndpoint` 646 →
469 LOC (-27%).

Cumulative impact since ADR-0052 started: **1934 → 469 LOC (-76%)**.
Backend runtime reads ONE opaque classpath resource
(`META-INF/quality-build-report.json`) for all build-time sections;
zero awareness of tool output shapes at request time. 17 dead K_
constants + 6 dead imports + 16 dead CP_/DEV_ path constants cleaned
up along the way.

### Q-3 — (optional, open) if dashboard ever needs Sonar/GitLab data inline

Revisit via build-time cached JSON injected by CI, NOT a runtime
REST call. New ADR rather than reopen ADR-0052.

---

## 🚦 Phase C — flip enforcement [UI DONE 2026-04-22, svc PENDING]

UI side shipped in MR !89 (commit cb246ca, tag
[stable-v1.0.21](https://gitlab.com/mirador1/mirador-ui/-/tags/stable-v1.0.21)).
ESLint flips warn → error on 6 size/complexity rules with project-
calibrated thresholds (max-lines 700, max-lines-per-function 100,
complexity 15, max-params 6, max-depth 4, max-nested-callbacks 4).
Two legitimate complexity violations carry inline disables with
documented reason (KeyboardService.onKeyDown switch-on-keycode,
TelemetryService.push level discrimination).

Svc side still pending — see notes below for the original plan
(failOnViolation flip on PMD + Checkstyle in pom.xml).

Once Phase B brings the current outliers below the new thresholds:

- `pom.xml` : `failOnViolation=true` on PMD + Checkstyle
- `pom.xml` : `<skip>false</skip>` on PMD + Checkstyle plugins (currently
  activated only via `-Preport` profile — flip to default)
- `eslint.config.mjs` : `warn` → `error` on the 6 size/complexity rules
- Suppression baseline files (created during B if needed) → delete

**Pre-requisite inventory step (~30 min, do first)**: enable PMD +
Checkstyle at build time with `failOnViolation=false` + `skip=false`,
run `mvn verify`, count violations per rule. If < ~20 violations
total → flip `failOnViolation=true` in the same MR and fix as we go.
If > 20 → create a suppressions baseline and decide per-rule what's
worth fixing before flipping.

Blocker: B-6 (dashboard.component .ts 1022 + .scss 1258) still
carries > 1000 LOC. Flipping Phase C before B-6 lands would trigger
FileLength rule → immediate red MR. Sequence: B-6 → Phase C inventory
→ Phase C flip. Then tag the next stable-vX.Y.Z. [B-5 done 2026-04-22]

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

### B1 + F1 — RxJS subscribe-leak cleanup (UI) [DONE 2026-04-23]

Shipped in UI MR !90 (commit 3243b58 + c7e3adc) tagged
[stable-v1.0.22](https://gitlab.com/mirador1/mirador-ui/-/tags/stable-v1.0.22).
76 `.subscribe()` calls now properly tied to a `DestroyRef` via
`takeUntilDestroyed()` across 14 components (quality, dashboard, security,
chaos, customers, diagnostic, database, activity, settings, login,
request-builder, pipelines, audit, maven-site-full) + 1 service
(FeatureFlagService).

Pattern: add `DestroyRef` to `@angular/core` import, inject
`private readonly destroyRef = inject(DestroyRef)` in the class header,
wrap every `.subscribe(` with `.pipe(takeUntilDestroyed(this.destroyRef))`
(or merge into existing `.pipe(...)` args). Two helper-context subscribes
in `find-the-bug.component` (scenario.trigger callbacks) intentionally
NOT migrated — their arrow signatures don't carry a DestroyRef and the
component's core flows are already protected.

### A3 — Sonar coverage to >80% on core module (svc) [PARTIAL 2026-04-23]

Was ~47%. **127 new tests added in one wave** across 14 test files
(13 svc + 1 UI) shipping under svc !150 + UI !91:

| File | Tests | Coverage area |
|---|---|---|
| `CustomerEnrichmentControllerTest` | 7 | bio + todos + enrich (incl. Kafka exception classifier) |
| `CustomerDiagnosticsControllerTest` | 7 | stream + slow-query (10s cap) + export (RFC 4180 escaping) |
| `SecurityDemoControllerTest` | 12 | OWASP demo endpoints (also fixed NPE on missing Origin) |
| `ApiKeyAuthenticationFilterTest` | 5 | X-API-Key flow + empty-key security guard |
| `AppUserDetailsServiceTest` | 5 | UserDetails translation + role mapping |
| `CustomerEnrichHandlerTest` | 4 | Kafka request-reply consumer |
| `CustomerEventListenerTest` | 3 | Async event consumer + counter |
| `ReportParsersTest` | 20 | Shared parser helpers + XXE hardening |
| `MaintenanceEndpointTest` | 5 | VACUUM endpoint + SQL allowlist guard |
| `DataInitializerTest` | 4 | Demo-user seeder idempotency + BCrypt-hash-before-save |
| `CreateCustomerRequestTest` | 10 | All Bean Validation constraints |
| `PatchCustomerRequestTest` | 7 | PATCH partial-update validation |
| `StartupTimingsControllerTest` | 5 | Diag endpoint shape + null-safe readyAt |
| `AuthControllerTest` | 7 | Login flow + IP extraction + brute-force guard |
| `quality-helpers.spec.ts` (UI) | 21 | severityColor / coverageColor / git URL helpers |

Production bug caught + fixed inline along the way:
`SecurityDemoController.corsInfo()` was passing `request.getHeader("Origin")`
directly to `Map.of()` — NPE on missing header. Same-origin requests +
curl probes don't send the header, so the demo endpoint was 500'ing on
benign callers. Coalesced to a placeholder string.

Estimated remaining to 80%: another 50-100 tests for AuthController
refresh/logout, JwtAuthenticationFilter, CustomerController helpers,
remaining quality parsers (Surefire/SpotBugs/PMD/Checkstyle/Owasp/Pitest),
Flyway migrations. Multi-session work.

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

### Régénérer la GIF demo du README (header)

`docs/media/demo.gif` (10 MB, 2026-04-21) est la première image que voit
un visiteur du repo (README.md ligne 55). Le contenu visuel a dérivé
depuis l'enregistrement (B-7 wave a refondu quality/security/dashboard
+ Phase 4.1 a re-câblé les SSE leaks + le tour-overlay a évolué). Le
script de régénération existe et est documenté inline en bas du README :

```bash
bin/record-demo.sh   # needs ffmpeg + the local stack up (./run.sh all)
```

**Pre-flight** (cf. memory `feedback_demo_recording_workflow.md`) :
1. `bin/healthcheck-all.sh` doit retourner all-green avant de lancer
   l'enregistrement (sinon le scénario va échouer au milieu).
2. Stack locale up : `./run.sh all` (db + kafka + obs + app +
   docker desktop tools).
3. Vérifier dwell timing dans le script — si trop court, le viewer
   ne distingue pas les sections; trop long → GIF gonfle.

**Estimé** : ~30 min (5 enregistrement + 10 GIF compression + 15
review qualité visuelle + commit).

**Quand le faire** : après la prochaine release stable significative
(ex: v1.1.0 quand on franchira un palier feature). Pas urgent —
l'ancienne GIF reste plausiblement représentative pour un visiteur
qui découvre le projet, juste un peu en retard sur les derniers
splits.

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
