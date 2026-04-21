# ADR-0042 — ESLint 9 + angular-eslint + SARIF wiring for UI quality gate

- Status: Accepted
- Date: 2026-04-21
- Deciders: @benoit.besson
- Related: ADR-0009 (UI quality pipeline), ADR-0037 (Spectral SARIF pattern)

## Context

Before this ADR the UI relied on three complementary tools for
static analysis:

- **TypeScript compiler (`tsc`)** — type errors, basic Angular
  checks (`NG8113` unused imports, etc).
- **Prettier** — code formatting only.
- **SonarCloud** — bugs, code smells, security hotspots after
  push to main (free tier has no PR analysis — see ADR-0041).

A code scan done at the time of this decision found patterns none
of the three catch reliably:

- **86 `.subscribe()` calls without `takeUntilDestroyed`** —
  classic Angular RxJS memory-leak shape.
- **12 `console.*` calls on production code paths** — debug noise
  left behind.
- **19 unhandled `.then/.catch`** (possible floating promises).
- **2 `*ngFor` without `trackBy`** (performance miss).

SonarCloud has TypeScript rules that overlap partially, but not
Angular-specific ones (RxJS subscribe patterns, template a11y,
`prefer-standalone`, control-flow migration). The gap is real
and well-defined: **Angular + RxJS lint**.

## Decision

**Adopt ESLint 9 (flat config) with the `angular-eslint` and
`typescript-eslint` plugin families. Emit findings in SARIF format
and feed them into SonarCloud as External Issues via
`sonar.sarifReportPaths`.**

### Concrete setup

- Dev dependencies added: `eslint@9`, `@eslint/js@9`,
  `typescript-eslint@8`, `angular-eslint@21`,
  `@microsoft/eslint-formatter-sarif`.
- Flat config at repo root: `eslint.config.mjs`.
- npm scripts: `lint`, `lint:fix`, `lint:sarif`.
- CI job: `lint:eslint` (stage `validate`) runs `npm run lint`
  AND `npm run lint:sarif`, uploads `eslint-report.sarif` as
  artifact (1-week retention).
- `sonarcloud` job `needs: lint:eslint` with `optional: true` +
  `artifacts: true` so SARIF is downloaded when present.
- `config/sonar-project.properties` adds
  `sonar.sarifReportPaths=eslint-report.sarif`.

### Rule severity philosophy

- **Error** on rules that guard NEW code only and don't grandfather
  legacy (selector style, `prefer-standalone`, `prefer-control-flow`,
  `click-events-have-key-events` after the info-tip fix).
- **Warn** on rules that flag existing tech debt
  (`no-explicit-any`, `no-console`, `array-type`, a11y rules on
  legacy templates, ReDoS regex edge cases).

Initial state after install + one `--fix` pass: **0 errors,
47 warnings**. CI passes from day one; warnings still surface in
SonarCloud External Issues so they're visible without blocking.

Rules migrate from warn → error as the corresponding tech debt
gets cleaned up (tracked in TASKS.md).

## Alternatives considered

### A) Continue without ESLint, rely on SonarCloud + tsc + Prettier

**Rejected.** SonarCloud's JS/TS analyzer doesn't cover Angular-
specific anti-patterns and can't catch RxJS subscribe-leak
patterns. tsc is type-only, Prettier is format-only. The 86
potential memory leaks alone justify the install.

### B) Add `@angular/cli` built-in lint schematic (`ng add @angular-eslint/schematics`)

**Partially adopted.** The schematic sets up ESLint for Angular
projects — we chose the same `angular-eslint` package family.
Skipped the wrapper because it assumes an angular.json `lint`
target and doesn't integrate a SARIF output format natively;
easier to configure flat config + SARIF directly.

### C) Rely on Biome instead of ESLint

**Rejected.** Biome is faster and has a cleaner config but does
NOT have Angular-specific rules (RxJS subscribe, Angular template
a11y, control-flow migration). Adopting Biome would require
writing those rules from scratch. Biome was already evaluated
and deferred in [ADR-0035](0035-defer-pact-and-biome.md).

### D) StyleLint for SCSS

**Out of scope** for this ADR. SCSS lint would be a separate
tool with its own rules and SARIF story; tracked as a later
follow-up if SCSS complexity warrants it.

## Consequences

**Positive**:
- 86 potential RxJS subscribe leaks are now tracked systematically;
  each migration to `takeUntilDestroyed(destroyRef)` clears one
  warning.
- ESLint findings are co-located with Java findings on SonarCloud's
  Issues tab — reviewers don't need a second UI.
- Real-time IDE feedback: VSCode + ESLint extension shows errors
  as the dev types, vs SonarCloud's 2-min post-push feedback loop.
- `angular-eslint` tightens NEW code (`prefer-standalone`,
  `prefer-control-flow`) so Angular 21's zoneless + signals
  patterns don't regress to NgModule/`*ngIf`.

**Negative**:
- 5 new dev dependencies (eslint + 4 plugins) and a new CI job.
  Renovate `digest` auto-bumps cover upgrades; peer-dep surprises
  possible on major bumps (saw `@eslint/js@10` conflict at
  install time, fixed by pinning to `@eslint/js@9`).
- CI adds ~30 s for `lint:eslint` on every MR — acceptable
  given the MR budget but not free.
- Warning-count creep: 47 today can drift to 80 if rules aren't
  tightened as tech debt is cleaned. Mitigated by the TASKS.md
  "B1+F1 ESLint cleanup" scheduled session.

## Operational notes

- **Flat config only** — the project uses ESLint 9 flat config
  (`eslint.config.mjs`), no `.eslintrc.json`.
- **Running locally**: `npm run lint` (check), `npm run lint:fix`
  (auto-fix Array<T>→T[], import order, etc).
- **SARIF generation**: `npm run lint:sarif` emits
  `eslint-report.sarif`. The `|| true` at the end ensures the
  file is written even if there are errors — we want Sonar to
  see them, not the CI to bail early.
- **CI path filter**: `lint:eslint` runs on every MR + main (no
  path filter). TypeScript/HTML is the bulk of the UI, most
  MRs touch it.

## Revisit criteria

- Biome reaches feature parity with `angular-eslint` for the
  RxJS + Angular-template rules we rely on → revisit ADR-0035
  + this one together.
- SonarCloud adds native Angular rules that cover the same gap
  → evaluate dropping ESLint in favour of Sonar's own analyzer.
- ESLint 10 lands + ecosystem follows → major-version upgrade
  MR, check for peer-dep breakage similar to the
  `@eslint/js@10` incident caught at install.

## References

- `eslint.config.mjs` — flat config + rule rationale.
- `.gitlab-ci.yml` — `lint:eslint` job, `sonarcloud` needs block.
- `config/sonar-project.properties` — `sonar.sarifReportPaths`.
- UI MR `!66` (stable-v1.0.8 era) — install + initial 0-error
  state.
- ADR-0009 — UI quality pipeline history.
- ADR-0035 — Biome deferral (revisit trigger).
- ADR-0037 — Spectral SARIF pattern (same mechanism, different
  source tool).
