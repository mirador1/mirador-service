# Quality-tool thresholds — industry baseline vs Mirador current state

**Generated**: 2026-04-21 · **Scope**: both repos (svc Java + UI TypeScript)
· **Phase**: A (audit + baseline), precedes Phase B (fix outliers) + Phase C
(flip `failOnViolation=true`).

## Why this doc exists

User feedback 2026-04-21 during a long session: "Je m'étonne que les outils
de qualité n'aient pas levé de warning sur la taille des fichiers". Audit
showed PMD + Checkstyle DO run but both with `failOnViolation=false`, so
real findings (god class, NCSS count, method length) go silently into
`/actuator/quality` reports that nobody reads. ESLint on the UI side
doesn't even have `max-lines` or `complexity` rules active. SonarCloud
is gated to main-only (ADR-0041) so MR-time feedback is absent.

This doc establishes the industry-standard thresholds we'll adopt and
tracks the delta vs current config. Phase B refactors bring outliers
under the line; Phase C flips enforcement on.

## Java / PMD thresholds — `config/pmd-ruleset.xml`

Current ruleset enables **design + bestpractices + errorprone + multithreading
+ performance + security + (partial) codestyle + (partial) documentation**
— 7 of 8 PMD Java categories. Size-related rules all sit under `design`
and are enabled by default (no exclusions in the current ruleset).

| Rule | PMD default | Industry baseline | Proposed for Mirador | Rationale |
|---|---:|---:|---:|---|
| `NcssCount` (class) | 1500 | 500-1000 | **750** | God-class detection; 1500 is PMD's "very large" floor — too permissive. 750 catches the 1934-line `QualityReportEndpoint` |
| `NcssCount` (method) | 60 | 40-60 | **50** | Industry middle; matches Sonar `java:S138` default (100 lines ≈ 50 NCSS) |
| `CyclomaticComplexity` (method) | 10 | 10-15 | **10** | PMD default is industry standard; don't relax |
| `CyclomaticComplexity` (class) | 80 | 50-80 | **60** | Tighten from 80 → 60; same spirit as method tightening |
| `CognitiveComplexity` | 15 | 15-25 | **15** | Default is industry; cognitive is harder to fight than cyclomatic |
| `TooManyMethods` | 10 | 20-30 | **25** | PMD default is TOO strict for Spring Boot (lots of `@GetMapping` per controller is legitimate); industry middle |
| `TooManyFields` | 15 | 10-15 | **15** | PMD default is industry ceiling; keep |
| `ExcessivePublicCount` | 45 | 30-50 | **40** | Slight tighten from default 45 |
| `ExcessiveImports` | 30 | 20-30 | **30** | Keep default; the 3rd-party BOM dependency explosion means 20 would be too strict |
| `ExcessiveParameterList` | 10 | 4-7 | **7** | Tighten dramatically — PMD default 10 is wrong; industry standard 4-7 is sound (Uncle Bob says 3 max) |
| `GodClass` | WMC>47 ATFD>5 TCC<0.33 | same | **same** | PMD defaults are the Lanza & Marinescu metric; well-established, don't touch |

### Currently impacted svc files (projected, pre-refactor)

- `QualityReportEndpoint.java` 1934 lines → `NcssCount` (class), `TooManyMethods`, `ExcessivePublicCount`, `CognitiveComplexity`, `GodClass` likely
- `CustomerController.java` 748 lines → `NcssCount` borderline, `TooManyMethods` (~20 endpoints), `ExcessiveImports` possible
- `run.sh` — not Java, not checked by PMD
- `stability-check.sh` — idem

## Java / Checkstyle — swap `google_checks.xml` → custom

Current config: `<configLocation>google_checks.xml</configLocation>` — the
built-in Google style. Google's ruleset **does not include size rules**
(`FileLength`, `MethodLength`, `LineLength`, `ExecutableStatementCount`).
Those are in Sun's `sun_checks.xml` but it's dated (Java 5 era).

**Proposed**: custom config `config/checkstyle.xml` that imports Google
as a base and adds:

| Check | Sun default | Industry baseline | Proposed |
|---|---:|---:|---:|
| `FileLength` | 2000 | 500-1000 | **1000** |
| `MethodLength` | 150 | 50-100 | **100** |
| `LineLength` | 80 | 100-120 | **120** (matches our Prettier config for TS) |
| `ExecutableStatementCount` | 30 | 30 | **30** |
| `ParameterNumber` | 7 | 4-7 | **7** |
| `AnonInnerLength` | 20 | 20-40 | **30** |

## TypeScript / ESLint — `eslint.config.mjs` (UI)

Current config: `@typescript-eslint/recommended` + `angular-eslint` +
`@eslint/js`. **Zero size-related rules active** — `max-lines`,
`max-lines-per-function`, `complexity`, `max-params`, `max-depth` are
all off by default and not re-enabled.

| Rule | ESLint default | Industry baseline | Proposed for Mirador | Rationale |
|---|---:|---:|---:|---|
| `max-lines` | off | 300-500 | **400** (warn) | Per-file ceiling; aligns with Angular cookbook "small components" |
| `max-lines-per-function` | off | 50-100 | **80** (warn) | Per-function ceiling |
| `complexity` | off (20 if on) | 10-15 | **10** (warn) | Cyclomatic; matches PMD side |
| `max-params` | off (3 if on) | 4-7 | **5** (warn) | Slightly above industry, generous for DI |
| `max-depth` | off (4 if on) | 3-4 | **4** (warn) | Default is industry; keep |
| `max-nested-callbacks` | off (10 if on) | 3-4 | **4** (warn) | Async code shouldn't nest callbacks deeply |

All start at `warn` (not `error`) so the initial enablement doesn't
fail CI. Flip to `error` in Phase C after the Phase B refactor clears
the current offenders.

### Currently impacted UI files (projected)

- `dashboard.component.ts` 1022 → `max-lines` warn
- `customers.component.ts` 904 → `max-lines` warn
- `quality.component.ts` 796 → `max-lines` warn
- `quality.component.html` 1742 → ESLint HTML does not check lines; caught by Sonar `typescript:S104` + our custom stability-check file-length gate

## SonarCloud — built-in rules, already active

Rules that DO fire but are gated to main-only (ADR-0041):

- `java:S138` Methods too long (100 lines)
- `java:S1200` Class coupling
- `java:S2094` Classes only with private methods/fields
- `typescript:S104` Files too long (1000)
- `typescript:S138` Functions too long (100)
- `typescript:S3776` Cognitive Complexity (15)

No config change needed; these surface after main pipeline. Free tier
cannot do PR analysis (documented in ADR-0041).

## Custom gate: `bin/dev/stability-check.sh section_file_length`

New section shipping today as Phase A's quick-win deliverable. Fails
stability-check when **any hand-written file ≥ 1 500 lines** — the
"split NOW" tier per the new `File length hygiene` rule.

**Current offenders** (at audit time):
- svc `.gitlab-ci.yml` — 2 619 lines (CI config; exempted via allowlist until modularisation ships)
- svc `pom.xml` — 2 217 lines (Maven monorepo; permanently exempted)
- svc `QualityReportEndpoint.java` — 1 934 lines (Phase B-1 split target)
- UI `quality.component.html` — 1 742 lines (Phase B-5 split target)

Allowlist lives inside `stability-check.sh` with a short rationale per
entry. Each Phase B commit removes the entry it resolves.

## Phase sequencing

1. ✅ **Phase A** (this doc + section_file_length + PMD tightening +
   printFailingErrors=true + ESLint warnings) — ships today.
   `failOnViolation` stays `false`; signal becomes visible in CI logs +
   `/actuator/quality` report.
2. ⏳ **Phase B** (file splits, dedicated sessions) — takes outliers
   below the new thresholds one by one. Each split commit removes its
   entry from the stability-check allowlist.
3. ⏳ **Phase C** (~30 min, post-Phase-B) — flip `failOnViolation=true`
   on PMD + Checkstyle, flip ESLint rules from `warn` to `error`. At
   this point every NEW violation fails the MR.

## References

- `~/.claude/CLAUDE.md` → "File length hygiene" + "Clean Docker regularly"
- PMD 7 Java rules: <https://pmd.github.io/pmd/pmd_rules_java.html>
- Checkstyle checks: <https://checkstyle.sourceforge.io/checks.html>
- ESLint core rules: <https://eslint.org/docs/latest/rules/>
- Lanza & Marinescu *Object-Oriented Metrics in Practice* (2006) — GodClass thresholds
- Uncle Bob *Clean Code* Ch. 3: 4 is the max for function parameters
