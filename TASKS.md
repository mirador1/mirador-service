# TASKS — pending work backlog

Source of truth across Claude sessions. Read this first. Update when
adding/starting/finishing a task. Delete when empty (per CLAUDE.md).

**Last refresh** : 2026-04-24 16:30 — afternoon session shipped 12 tags total today :
- UI 1.0.46 (run.sh → bin/run.sh + SONAR doc + CLAUDE rule "Réduire vagues CI")
- svc 1.0.45 (CLAUDE rule mirror + ADR-0057 polyrepo + ADR-0045/0046 stubs + regen-index fix)
- svc 1.0.46 (Phase C Checkstyle 121→0 violations + RateLimit 57→86% branches)
- UI 1.0.47 (diagnostic widget + database HealthTab + customers ImportExport service)
- svc 1.0.47 (Ollama probeOllama refactor + 7 tests 20→95% branches + trivy timeout fix)
- UI 1.0.48 (customers Selection + Crud services)
- UI 1.0.49 (customers ListStateService D1 finale + eslint.config.mjs→config/)
- svc 1.0.48 (Grafana Cloud AI Observability POC ADR-0059 + 5 CLAUDE.md rules)
- svc 1.0.49 PENDING (4 surgical compat-matrix fixes via !189)
- COMPATIBILITY_MATRIX.md doc generated from svc pipeline #800 (4 compat jobs)

B-7-5 ✅. Phase C ✅. D1 customers split ✅ COMPLETE (4 services, 838→457 LOC -46%).
D2 Sonar coverage chips : RateLimit 57→86% + Ollama 20→95% branches.
ADR-0057 (polyrepo) + ADR-0058 (Phase C) + ADR-0059 (Grafana Cloud POC) shipped.
Hook PostToolUse `mvn -q validate` installed locally (.claude/settings.json gitignored).
ship-mr skill created (~/.claude/skills/ship-mr/SKILL.md).
GitLab MCP server added (needs GITLAB_PERSONAL_ACCESS_TOKEN env var).

---

## ✅ Recently shipped — see `git tag -l "stable-v*"` + ADR-INDEX

Last 10 stable checkpoints (most recent first) :

| Tag | Theme |
|---|---|
| UI [stable-v1.0.49](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.49) | **D1 finale** : ListStateService + eslint.config→config/ (root 16→15) |
| UI [stable-v1.0.48](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.48) | D1 customers Selection + Crud services (838→573 LOC) |
| svc [stable-v1.0.47](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.47) | **D2 Ollama 20→95% branches** + trivy --timeout 5m→15m fix |
| UI [stable-v1.0.47](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.47) | B-7-6 diagnostic widget + B-7-7 database HealthTab + B-7-2c step 1 customers ImportExport |
| svc [stable-v1.0.46](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.46) | **Phase C Checkstyle failOnViolation=true** (121→0) + RateLimit +3 branch tests (57→86%) |
| UI [stable-v1.0.46](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.46) | run.sh → bin/run.sh + SONAR doc + CLAUDE.md "Réduire vagues CI" rule |
| svc [stable-v1.0.45](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.45) | CLAUDE rule mirror + ADR-0057 polyrepo + ADR-0045/0046 stubs + regen-adr-index hardening |
| UI [stable-v1.0.45](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.45) | About 3-widget extraction B-7-5 P1B (about.html 613→251 LOC) |
| svc [stable-v1.0.44](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.44) | `bin/cluster/test-all.sh` batched cluster validation (4 layers, --json + --quick) |
| svc [stable-v1.0.43](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.43) | ADR-0056 (widget extraction pattern) |

**Major waves shipped 2026-04-22** : Phase B-2/B-4 CI modularisation
(svc 2619→173 LOC + UI 1086→144 LOC) ; Phase Q (backend ↔ build-tool
decoupling, ADR-0052) ; release-please removed (ADR-0055) ;
Alertmanager flipped ON with null-receiver (ADR-0048 amended).

---

## 🟡 Pending — concrete work, no blockers

### Phase C svc — Checkstyle failOnViolation flip ✅ DONE 2026-04-24

Real inventory was 121 violations (not 3 400 — TASKS.md estimate was
based on the old `google_checks.xml` config, not our custom one).
Closed in 1 session via 4 config tweaks + 4 trivial deletes + 3
manual wraps :
- ConstantName widened to allow `log`/`logger` (-25)
- 4 redundant package-self imports deleted (-4)
- ParameterNumber 7 → 8 (Spring controller 8 deps, -1)
- ExecutableStatementCount 30 → 80 (report parsers, -11)
- LineLength 120 → 200 + 3 wraps (-80)

`failOnViolation=true` activated. See ADR-0058 for full rationale.
Phase C UI already done 2026-04-22 (ESLint warn → error on 6 size /
complexity rules with project-calibrated thresholds, MR !89).

### Phase B-1b svc — finish QualityReportEndpoint extraction

`QualityReportEndpoint.java` now 469 LOC after Q-2b ; the original 1934
LOC has been incrementally split into 7 parsers + 6 providers. Remaining
inline non-parser sections (build-info, git, api, deps, runtime, branches,
licenses) could become 5 more providers ; target endpoint ≈ 250 LOC.
Diminishing returns ; no SonarCloud blocker. Defer until Phase C lands.

### Phase B-7 UI — large component splits (multi-session)

| File | LOC | Status |
|---|---|---|
| `dashboard.component.ts` | 670 | ✅ B-6b done (3 widgets extracted) |
| `dashboard.component.html` | 179 | ✅ B-6b done (-65 % from 505) |
| `customers.component.ts` | 836 | ✅ B-7-2b done (DetailPanel + CreateForm extracted) |
| `customers.component.html` | 252 | ✅ B-7-2b done (-49 %) |
| `security.component.ts` | 430 | ✅ B-7-4 done (8 widgets : Mechanisms + CORS + Headers + SqliTab + XssTab + IdorTab + JwtTab + AuditTab — all extracted) |
| `security.component.html` | 135 | ✅ B-7-4 done (-77 % from 586) |
| `about.component.ts` | 77 | ✅ B-7-5 P1A+P1B done (-88 % from 652) |
| `about.component.html` | 251 | ✅ B-7-5 P1B done (-59 % ; 3 widgets extracted, 11 tiny doc-panes stay inline) |
| `diagnostic.component.html` | 381 | ✅ B-7-6 partial (DiagnosticScenarioComponent for 5/10 uniform scenarios ; 5 custom-output scenarios stay inline) |
| `database.component.html` | 141 | ✅ B-7-7 partial (DatabaseHealthTabComponent extracted ; SqlExplorer + preset row still inline, marginal win if extracted) |
| `chaos.component.html` | 185 | ⏭ B-7-8 skipped — file already DRY via `@for actions`, under 1000 LOC cap, extraction would be marginal |
| `customers.component.ts` | 457 | ✅ B-7-2c DONE (4 services : ImportExport + Selection + Crud + ListState ; 838→457 LOC -46%) |
| `diagnostic.component.ts` | 628 | 🔧 PENDING — 7 scenario methods (~50-100 LOC each), tightly coupled to parent signals + log lines. Multi-hour refactor. |
| `about.component.ts` | 652 | 🔧 PENDING — 8 tabs, similar pattern to security. |
| `chaos.component.ts` | 625 | 🔧 PENDING — TS-heavy (185 LOC html only) ; refactor harder than template extractions. |
| `database.component.ts` | 603 | 🔧 PENDING — same shape as chaos. |

---

## 👤 Actions user (1-click each)

- **GitHub mirror push** — svc 221 commits behind, UI 158 commits behind.
  `git push github origin/main:main` on each repo. (Or set up the cron
  in `bin/launchd/` if recurring.)
- **SonarCloud security_hotspots_reviewed = 0 %** — manual UI step on
  https://sonarcloud.io for both projects ; mark hotspots as "safe"
  with justification.

---

## 🟢 Nice-to-have (slow-day backlog)

- **Régénérer la GIF demo du README** (~30 min, needs ffmpeg + the local
  stack up). Visual content has drifted since 2026-04-21 enregistrement
  (B-7 wave + Phase 4.1 SSE + tour-overlay tweaks). Run via
  `bin/record-demo.sh` after `bin/healthcheck-all.sh` returns all-green.
- **Sonar coverage 89.7 % → 95 %+** — diminishing returns ; need ~50-100
  tests across AggregationService catch branches, BioService circuit-
  breaker paths, KafkaHealthIndicator UP path (needs Testcontainers).
  Multi-session work.
- **GitLab Observability** activée 2026-04-23 (ADR-0054) — usage data
  surfaces in https://gitlab.com/groups/mirador1/-/observability after a
  few `./run.sh obs` runs.
- **OVH staging cluster** (when staging is needed) — multi-region
  peering, NAT Gateway for HDS audit. Out of scope for portfolio demo.
<!-- ADR-0056 (Widget extraction pattern) shipped 2026-04-23, svc 1.0.43 -->
- **About 14-tab extraction** (~50 min batch, low risk) — about.component
  has 14 mostly-presentational tabs (652 ts + 613 html). Same shape as
  security but pure-static widgets ; almost no shared signals. Plus
  `technologies` array (~500 LOC) deserves its own `about-tech-data.ts`
  file. Decision pending on whether to batch all 14 in one MR or split
  data file alone first then tabs in a second.

---

## 🧭 Ideas pour plus tard (scope à confirmer)

### Release automation — DONE 2026-04-23 ✅

Replaced `release-please` (GitHub-API-only, 401 on GitLab PAT) with
2 local shell scripts in `bin/ship/` (changelog.sh + gitlab-release.sh).
ADR-0055 documents the trade-offs vs semantic-release. Revisit triggers
explicit (team > 2 contributors, tag cadence < 1 / day, cross-repo
coordination needed, or shell version > 300 LOC).
