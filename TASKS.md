# TASKS — pending work backlog

Source of truth across Claude sessions. Read this first. Update when
adding/starting/finishing a task. Delete when empty (per CLAUDE.md).

**Last refresh** : 2026-04-24 02:21 — after 3 fresh tags : svc 1.0.44
(cluster test-all.sh) + UI 1.0.44 (security 5-widget batch + about
data P1A) + UI 1.0.45 (about 3-widget P1B → about.html 613→251 LOC).
Phase B-7-5 fully DONE. Next B-7 offenders : diagnostic (628),
chaos (625), database (603). Historical DONE entries collapsed to
one-line refs ; detail lives in `git log` + ADRs + tag annotations.

---

## ✅ Recently shipped — see `git tag -l "stable-v*"` + ADR-INDEX

Last 10 stable checkpoints (most recent first) :

| Tag | Theme |
|---|---|
| UI [stable-v1.0.45](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.45) | About 3-widget extraction B-7-5 P1B (about.html 613→251 LOC) |
| svc [stable-v1.0.44](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.44) | `bin/cluster/test-all.sh` batched cluster validation (4 layers, --json + --quick) |
| svc [stable-v1.0.43](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.43) | ADR-0056 (widget extraction pattern) |
| svc [stable-v1.0.42](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.42) | ADR index refresh + audit artefact |
| svc [stable-v1.0.41](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.41) | Coverage : ApiError 0→100% + Ollama DOWN branch |
| svc [stable-v1.0.40](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.40) | OVH per-resource timing docs |
| svc [stable-v1.0.39](https://gitlab.com/mirador1/mirador-service/-/releases/stable-v1.0.39) | Q2 OVH activated + GitLab Observability + 4 CI fixes |
| UI [stable-v1.0.44](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.44) | Security 5-widget batch (B-7-4 ✅) + About data extraction Phase 1A |
| UI [stable-v1.0.43](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.43) | TASKS.md cleanup (635→115 LOC) |
| UI [stable-v1.0.42](https://gitlab.com/mirador1/mirador-ui/-/releases/stable-v1.0.42) | Security MechanismsTab widget extraction (B-7-4) |

**Major waves shipped 2026-04-22** : Phase B-2/B-4 CI modularisation
(svc 2619→173 LOC + UI 1086→144 LOC) ; Phase Q (backend ↔ build-tool
decoupling, ADR-0052) ; release-please removed (ADR-0055) ;
Alertmanager flipped ON with null-receiver (ADR-0048 amended).

---

## 🟡 Pending — concrete work, no blockers

### Phase C svc — Checkstyle failOnViolation flip

Inventory shows ~3 400 violations (2 660 IndentationCheck = 78 % of
the long tail). Pragmatic plan, ~3-4 h dedicated session :
1. Silence IndentationCheck globally (style-only, no correctness value)
   → drops to ~740 violations
2. Clear LineLengthCheck (301) via `mvn formatter:format`
3. Clear CustomImportOrderCheck (161) via IDE organize-imports
4. Flip `failOnViolation=true` on PMD + Checkstyle in pom.xml once
   < 50 remaining
5. ADR for Phase C svc acceptance criteria

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
