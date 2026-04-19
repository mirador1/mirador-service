# ADR-0029 — Jenkinsfile parity demonstrator + declarative-linter validation

- **Status**: Accepted
- **Date**: 2026-04-19
- **Related**: [ADR-0004](0004-local-ci-runner.md) (local GitLab runner),
  [docs/ops/ci-philosophy.md](../ops/ci-philosophy.md), [docs/ops/jenkins.md](../ops/jenkins.md)

## Context

The canonical CI is GitLab. The project already ships narrow GitHub
Actions for CodeQL + Scorecard (see
[`docs/ops/ci-philosophy.md`](../ops/ci-philosophy.md)). What's missing
is evidence that the **industrial tooling** we rely on (Testcontainers,
SBOM, cosign, PIT, Sonar, Semgrep, Grype, Syft) is portable to
**Jenkins** — the dominant CI in enterprise shops (banking, insurance,
telco, public sector) that cannot run GitLab SaaS on their private
network.

Without a Jenkinsfile, a prospective adopting team has no way to
answer "will this stack run on our Jenkins?" except by spending a
week rewriting the pipeline themselves. That's a portability
liability.

Two questions:

1. **Do we ship a `Jenkinsfile`?** If yes, what scope?
2. **How do we validate it without running a persistent Jenkins?**

## Decision

### Ship a `Jenkinsfile` as a parity demonstrator, not a live pipeline

The file lives at the repo root, written in declarative pipeline
syntax, matching the GitLab stages that have a sensible Jenkins
equivalent: lint, unit tests, integration tests, Sonar, package,
supply-chain (SBOM + scan + cosign), PIT mutation.

Skipped stages: `test:k8s-apply` (kind + Docker-socket model differs
on Jenkins), `terraform-plan` / `deploy:gke` (GCP credentials plugin
is environment-specific), matrix compat builds (trivially expressible
but add 5 parallel stages without new concepts).

The Jenkinsfile is **inert** — not wired to any running Jenkins, not
automatically executed, not required to stay strictly in lock-step
with `.gitlab-ci.yml`. It is a **demonstrator**, not a canary. The
distinction is critical: if we also ran it as a live pipeline, we'd
double the CI cost and maintenance surface, and the team would ask
"which one do I trust when results diverge?" — the exact trap we
avoid on GitHub (see ci-philosophy.md).

### Validate via option 2: Jenkins declarative linter in a throwaway Docker

Three options considered for validation:

| Option | Coverage | Speed | Setup |
|---|---|---|---|
| **1. `jenkinsfile-runner` CLI** | Runs the pipeline start-to-finish | 5–10 min | Docker image available, but missing our plugins (Docker Pipeline, JaCoCo) → not exec-compatible with our file |
| **2. Declarative linter via ephemeral Jenkins** | Syntax-only (stages, when, agent, directives) | ~60 s cold, ~10 s warm | 1 Docker container, automatically torn down after the run |
| **3. Full local Jenkins** | Full exec with plugins | 10 min setup, ~5 min per run | Persistent container, manual plugin install |

**Chosen: option 2.** Reasoning:

- **Scope match.** We ship the file for *portability* (can a team
  adopt it). The primary failure mode we need to catch is syntactic
  drift — someone edits the Jenkinsfile to match a new GitLab stage
  but typos a directive, breaks Groovy, or mis-nests `stages {}`.
  Syntax validation catches all of that.
- **CI integration.** Option 2 is cheap enough to run on every push
  that touches the Jenkinsfile (pre-commit hook or GitLab CI stage).
  Option 1 and 3 are too slow / too fragile.
- **No infra to maintain.** The throwaway container has a lifetime
  of one invocation. No stale Jenkins home, no plugins to keep up
  to date, no "wait, is my Jenkins still listening?".
- **Honest about what it doesn't cover.** Shell commands inside each
  stage, missing credentials, Docker image pull failures, agent
  label mismatches — all invisible to a linter. That's fine: the
  Jenkinsfile is a demonstrator, the adopting team runs the real
  pipeline in their own environment and surfaces those errors there.

The helper lives at [`bin/jenkins-lint.sh`](../../bin/jenkins-lint.sh).
Idempotent, self-cleaning, pinned Jenkins image.

## Consequences

### Positive

- **Adoption question answered.** A team evaluating Mirador for their
  Jenkins shop can clone, run `bin/jenkins-lint.sh`, get a pass in
  under a minute, read [`docs/ops/jenkins.md`](../ops/jenkins.md),
  and know the stack fits.
- **Low maintenance.** The Jenkinsfile doesn't need to run on every
  push — it only needs to parse. Adding a new GitLab stage usually
  doesn't require updating the Jenkinsfile same-session.
- **No duplicated CI cost.** Zero GitLab pipeline minutes spent
  running Jenkins in parallel.
- **Explicit "demonstrator" posture.** The `ci-philosophy.md` hard
  rule — "Jenkinsfile is inert parity, not a canary" — means lag is
  acceptable and tracked.

### Negative

- **The Jenkinsfile can become stale** without anyone noticing until
  a syntax check catches it. Mitigation: the lint script is small
  enough to add as a pre-commit hook or as a GitLab CI stage on
  Jenkinsfile-touched pushes (not done yet; tracked in ROADMAP if
  Jenkins adoption becomes real).
- **Syntax pass ≠ working pipeline.** A Jenkinsfile can lint-pass
  and still fail at runtime (missing plugins, shell errors, credential
  names). The README in `docs/ops/jenkins.md` is explicit about this.
- **New plugin needed** — the declarative-linter requires the
  `pipeline-model-definition` plugin, which is bundled in the
  standard `jenkins/jenkins:lts-jdk25` image we use, but not in the
  minimal `jenkinsfile-runner` image.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Drop the Jenkinsfile entirely | Loses portability story — adopting team has to write it from scratch. |
| Live Jenkins as a third real CI | Doubles cost + drift risk (ci-philosophy.md Rule 3). |
| `jenkinsfile-runner` as the linter | Image doesn't include our plugins; each stage fails on unrelated issues; noisy. |
| A third-party online linter | Doesn't exist as a trusted service (see jenkins.md "Services en ligne"). |
| GitHub Actions runner for Jenkinsfile | Misses the point — Jenkinsfile adoption is for Jenkins shops, not GitHub Actions shops. |
| Skip validation, review by eye | Groovy mis-nesting is exactly the class of error eyes miss. The 60 s lint is worth it. |

## Revisit this when

- **A real team adopts Mirador on Jenkins.** The Jenkinsfile becomes
  a canary at that point — promote it, stop treating it as a
  demonstrator, wire it into `bin/mirador-doctor`.
- **GitLab SaaS becomes unavailable** (paid-tier only, private
  networks, outages). Jenkins may become canonical.
- **Jenkins releases a trustworthy hosted linter** (unlikely but
  would simplify `bin/jenkins-lint.sh` to a curl call).
- **The Jenkinsfile drifts > 10 stages behind** `.gitlab-ci.yml`.
  That's the threshold where the parity claim stops being credible
  and we should either catch up or document the gap.
