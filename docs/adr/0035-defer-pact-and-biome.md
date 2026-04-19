# ADR-0035 — Defer Pact + Biome adoption (proposals #5 + #17)

- **Status**: Accepted (deferred adoption)
- **Date**: 2026-04-19
- **Related**: [ADR-0033](0033-playwright-e2e-in-kind-in-ci.md) (Playwright E2E
  is the layer that catches the bugs Pact would catch),
  [ADR-0029](0029-jenkinsfile-parity-and-declarative-linter.md) (lint surface
  philosophy)

## Context

Two adoption proposals raised in the 2026-04-19 industrialisation
session need a recorded "no — for now" decision so they don't keep
showing up in every backlog review without context.

### Proposal #5 — Pact contract tests UI ↔ Backend

[Pact](https://docs.pact.io/) implements consumer-driven contract
testing. The UI (consumer) records its expectations of the backend
in a `.pact.json` file; the backend (provider) verifies those
expectations against the actual implementation.

Pro: catches breaking API changes at build time, before either side
deploys. Replaces ad-hoc "did anyone tell the UI we renamed `email`
to `emailAddress`?" pain.

Con: requires Pact JS on the UI side, Pact JVM on the backend side,
a Pact broker (or file-based shared storage), and a CI job per side
that runs the verification. ~1 day of setup; ongoing maintenance per
new endpoint (~5 min each).

### Proposal #17 — Biome (replace ESLint + Prettier)

[Biome](https://biomejs.dev) is a single Rust-based formatter +
linter that subsumes ESLint + Prettier. ~10× faster, single config
file, no plugin system to maintain.

Pro: fewer dependencies, one config, faster lint in CI + lefthook.

Con: replacing two well-tested tools that already work. Migration
runs Biome's formatter over the entire codebase → produces thousands
of cosmetic diffs that dominate one MR and obscure real changes.
Existing ESLint rules need translation (Biome covers ~70 % of
common ESLint rules; the remaining 30 % need either Biome plugins,
some of which don't yet exist, or to stay in ESLint alongside).

## Decision

**Defer both. Re-evaluate when the trigger condition fires.**

### Pact — defer until either trigger fires

1. **A real consumer-provider mismatch reaches main** despite
   Playwright E2E (ADR-0033) running on every MR. The E2E suite
   today already catches "the backend changed, the UI doesn't
   render correctly" because both are exercised in one browser
   pass. Pact would catch the SAME bugs earlier (build vs E2E
   stage), but at the cost of a separate test layer to maintain.
   The first time E2E *misses* a contract regression is the day
   we ship Pact.

2. **A second UI consumer joins the backend** (mobile app, partner
   integration, internal admin console). The day the backend has
   N consumers, Pact's "every consumer publishes its expectations"
   model becomes net-positive even with E2E in place.

Until then: **the ROI doesn't justify the maintenance cost on a
single-consumer / single-provider portfolio demo.**

### Biome — defer until either trigger fires

1. **ESLint or Prettier breaks** in a way the project can't easily
   work around (e.g. abandoned plugin, security CVE in a transitive
   dep, perf regression > 30 % on a CI run). Today's setup is
   working — change for change's sake is a poor reason to introduce
   a multi-thousand-line cosmetic MR.

2. **The team grows** to a point where lint speed becomes a friction
   ('npm run check' currently completes in ~4 s; Biome would shave
   it to <1 s, but 4 s is invisible at the current commit cadence).

Until then: **the migration cost (review fatigue + risk of subtle
rule-translation gaps) outweighs the speed-and-simplicity win.**

## Consequences

### Positive

- **No yak-shaving** — the team focuses on shipped product, not
  on migrating a green-pipeline lint stack to a marginally-better
  green-pipeline lint stack.
- **Pact / Biome stay on the radar** — this ADR is the audit trail
  for "we considered them, here's why later not now".

### Negative

- **Pact-class bugs are still possible** between Playwright runs
  (e.g. an API field rename that the E2E test doesn't exercise).
  Mitigation: when adding a new endpoint, ALSO add a Playwright
  spec that exercises it via the UI (the unwritten rule today).
- **Biome catches a few rule classes** ESLint doesn't (e.g. some
  React/Angular pattern checks). Marginal — none flagged in the
  current codebase.

### Neutral

- **Re-evaluation cost is low** — both proposals have one-line
  triggers above. Next quarterly review just checks if the trigger
  fired.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Adopt Pact now** for the educational portfolio value | Playwright E2E (ADR-0033) already covers the same bug class for a single-consumer setup. Adding Pact alongside doubles the maintenance cost without doubling the safety. Educational value is documentable in the README ("we considered, here's why not"). |
| **Adopt Biome now** because it's modern | Migration produces thousands of cosmetic diffs (ESLint sort vs Biome sort, Prettier line-length vs Biome line-length), one MR that nobody can review meaningfully. Defer to a slow week. |
| **Pact + Biome both, batched together** | Worst of both worlds: massive MR, two unrelated risks, hard to bisect if anything regresses. |
| **Drop the proposals entirely** | They're worth re-evaluating when the trigger fires. Documenting the "not now" decision is cheaper than re-having the discussion. |

## Revisit this when

- Pact: **a real contract regression slips past Playwright E2E**, OR a
  second consumer of the backend appears (mobile, partner, internal admin).
- Biome: **ESLint/Prettier breakage** that's painful to work around, OR
  the team grows enough that lint speed becomes a daily friction point.
- Both: Annual review (next: 2027-04). If neither trigger fired and the
  context hasn't changed, mark this ADR `Confirmed` and skip again.
