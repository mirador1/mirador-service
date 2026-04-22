# ADR-0049: CI shields (`allow_failure: true`) require a dated exit ticket

- **Status**: Accepted
- **Date**: 2026-04-22
- **Deciders**: Mirador maintainers
- **Related**: [ADR-0041](0041-ci-hygiene-honest-green-discipline.md) (CI
  hygiene — scope-out > shield), `~/.claude/CLAUDE.md` → "Pipelines stay green"

## Context

Mirador's CI rule (ADR-0041) is "scope-out, not shield" — when a job can't
reliably pass under current conditions, prefer narrowing the trigger
(`rules.if`, `changes:`) over `allow_failure: true`. But there are real
cases where scope-out doesn't work:

1. **Infrastructure constraints** — `test:k8s-apply` + `test:k8s-apply-prom`
   need to run on every relevant manifest change, but kind clusters OOM the
   Docker Desktop VM (default 7.6 GB) when other CI work loads the runner.
   Can't scope out without losing the CRD-drift signal.
2. **Upstream multi-arch gaps** — `grype:scan` panics on arm64 runners
   because the official debug image is amd64-only. Can't scope out without
   losing all SCA signal.
3. **Cold-start variance** — `openapi-lint` boots a Spring app to fetch
   `/v3/api-docs`; the boot wait window has been bumped twice
   (60 s → 180 s → 300 s → 600 s) and still occasionally trips on a
   loaded runner.

Without a structured pattern, `allow_failure: true` can become permanent
tech debt — set once, never revisited, masking real regressions for years.
The 2026-04-21 session showed how easy it is to slap `allow_failure: true`
on three flaking jobs in a single afternoon.

## Decision

Every `allow_failure: true` in `.gitlab-ci.yml` MUST carry a 4-element
**exit ticket** as inline comment immediately above the directive:

1. **Pipeline reference** — the run where the shield was first justified
   (e.g. `pipeline #612 (2026-04-22) showed control-plane OOM during
   chaos-mesh CRD install`)
2. **Root cause** — one sentence on what's actually broken (NOT "flaky")
3. **Two clear exit paths** — what would let us remove the shield. At
   least one must be a concrete code/config change; the other can be an
   infra/user action with measurable effect
4. **Revisit date** — explicit `Revisit: YYYY-MM-DD`, ≤ 1 month out from
   the date the shield was added

**Format example** (as enforced in `.gitlab-ci.yml` since 2026-04-21):

```yaml
test:k8s-apply:
  # allow_failure: true shield re-armed 2026-04-21 — pipeline #610 control-
  # plane failed "≤ 2m0s for Ready" during chaos-mesh + kube-prom-stack CRD
  # install. Docker Desktop VM cap (7.6 GB) too tight under session load.
  # Two exit paths:
  #   1. Raise Docker Desktop VM ≥ 12 GB (user action, can't be CI-fixed)
  #   2. Move job off macbook-local to SaaS amd64 runner
  # Revisit: 2026-05-21.
  allow_failure: true
```

When the revisit date arrives:

- If neither exit path has landed → the shield is upgraded to a
  permanent decision and gets its own ADR (e.g. "ADR-00XX: grype:scan
  arm64 gap accepted, no SCA signal on Mac runners"). The ADR replaces
  the inline ticket.
- If one of the exit paths has landed → remove the shield, re-validate
  the job is genuinely green, commit.

`bin/dev/stability-check.sh` adds a check that flags any
`allow_failure: true` line whose nearest preceding `Revisit: YYYY-MM-DD`
comment is in the past (or absent).

## Consequences

### Positive

- Each shield is reviewable: the comment alone tells a future reader
  whether the workaround still applies.
- Exit dates create natural review points. The shield IS expected to
  go away; staying past the date is the exception, not the default.
- Migrating long-lived shields to ADRs gives them visibility (ADR
  table-of-contents entries are reviewable in MRs; inline pom comments
  aren't).

### Negative

- More verbose CI YAML — a 1-line directive becomes a 7-line block.
  Acceptable trade-off given the alternative (silent shields persisting
  indefinitely).
- Stability-check needs to parse comments — fragile to formatting
  changes (`# Revisit:` vs `# revisit:` vs `# Revisit -`). The check
  is permissive (regex `[Rr]evisit:?\s+(\d{4}-\d{2}-\d{2})`) but a
  contributor renaming the marker still breaks it silently.

### Neutral

- Existing shields pre-2026-04-21 (e.g. `.compat-job`, `semgrep`
  manual-only intent) get retroactive tickets during the next grooming
  pass — not a hard cut-over.

## Alternatives considered

### Alternative A — Hard ban on `allow_failure: true`

Force every flake to be either fixed or scoped-out. Pro: maximal CI
purity. Con: real-world infra issues (Docker VM cap, arm64 gaps) need
SOME way to land while the underlying problem is being addressed.
Strict ban makes "fix the runner" the prerequisite for "land the
feature", which serialises work artificially.

### Alternative B — Time-bombed shields enforced by CI

`allow_failure: true` automatically decays after the revisit date,
turning the job red. Pro: forces the conversation. Con: would break
on weekends/holidays when nobody can react; the soft-flag-only stability-
check approach gives the same nudge without the all-or-nothing failure.

### Alternative C — Move all shields to a separate `ci/shields.yml` include

Centralises the visibility but loses the local context (job + shield
together). Hard to maintain consistency between "the job lives here,
the shield lives there".

## References

- `docs/audit/quality-thresholds-2026-04-21.md` — Phase A audit, includes
  the same "signal first, enforce later" pattern at the lint level
- [ADR-0041](0041-ci-hygiene-honest-green-discipline.md) — scope-out preferred
- [ADR-0048](0048-prometheus-alert-rules-evaluate-but-dont-route.md) —
  same "deliberately documented limitation" framing for alert routing
- `~/.claude/CLAUDE.md` → "Pipelines stay green"
- Current shields with tickets (post-2026-04-21):
  - ~~`test:k8s-apply` + `test:k8s-apply-prom` (Revisit 2026-05-21)~~ — **retired 2026-04-22** (see log below)
  - `.compat-job` template, `semgrep`, `native-image-build` (manual-only
    intent, retroactive tickets pending)

## Shield-retirement log

First validation of the "dated exit ticket → real retirement" flow.

### 2026-04-22 — test:k8s-apply + test:k8s-apply-prom (commit `314012f`)

**Original ticket** (commit 3dcb2d0 + d54c5e9, 2026-04-21):

> Pipelines #610 + #612 showed Docker Desktop VM OOM (7.6 GB cap) during
> kind control-plane + chaos-mesh + kube-prom-stack CRD install. Two
> exit paths: (1) raise Docker Desktop VM ≥ 12 GB, (2) move jobs off
> macbook-local. Revisit: 2026-05-21.

**Exit taken**: Path 1. User raised Docker Desktop VM to **16 GB** (2 GB
above the stated minimum for headroom during multi-compose sessions).

**Validation performed**:
- `docker system info` → Total Memory: 15.6 GiB, CPUs: 10 ✅
- Shield-retirement commit [314012f](../commit/314012f) itself made via
  git commit — also served as the first SSH-signed commit under the
  parallel S2 signed-commits hardening (same session).

**Outcome**:
- Both `allow_failure: true` removed from `.gitlab-ci.yml`
- `resource_group: k8s-kind-cluster` + `retry: when: runner_system_failure`
  kept as belt-and-suspenders (harmless if OOM never recurs).
- Lead time ticket → retirement: ~18 hours. Well under the 1-month
  revisit date (2026-05-21). Exit pattern works.

**Regression plan**: if OOM re-emerges in a future pipeline, re-shield
with a FRESH dated ticket (not reuse the old one). Same pattern — this
section serves as the precedent.
