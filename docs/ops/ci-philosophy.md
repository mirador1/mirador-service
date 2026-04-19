# CI philosophy — why three CI systems, and how they fit together

Mirador ships three sets of CI configuration files. This page
explains what each one is for, which is authoritative, and why
duplication is carefully avoided.

## The three CI surfaces

| File | Platform | Status | Purpose |
|---|---|---|---|
| [`.gitlab-ci.yml`](../../.gitlab-ci.yml) | GitLab CI | **Canonical** (master) | The only CI that builds, tests, scans, signs and deploys. Every merge passes through it. |
| [`.github/workflows/*.yml`](../../.github/workflows/) | GitHub Actions | **Complement** | Runs ONLY what GitHub provides natively and GitLab can't match: CodeQL SAST + OSSF Scorecard. Nothing else. |
| [`Jenkinsfile`](../../Jenkinsfile) | Jenkins | **Parity demonstrator** | Not live. Shipped to prove the industrial tooling (Testcontainers, SBOM, cosign, Sonar, PIT) runs under Jenkins unchanged. Adoptable in enterprises locked into Jenkins. |

## The hard rules

These rules are **non-negotiable**. When they drift, the next session
has to reinstate them before doing anything else.

### Rule 1 — GitLab is master. Always.

- The canonical repo URL is `gitlab.com/mirador1/mirador-service` (or
  `-ui`). Every other surface (GitHub mirror, Artifact Registry
  image, Grafana dashboard) points back to it.
- The **branch protection** is on GitLab `main`: no direct push, MR
  required, `main` is protected, `dev` is the working branch.
- The **release process** (`release-please` + `v1.x` tags) runs on
  GitLab. Tags are pushed FROM GitLab and propagate OUT.
- If a change exists on GitHub but not on GitLab, **GitLab wins** and
  the GitHub change is discarded. There is no reverse sync path.

### Rule 2 — GitHub is slave, read-only, narrow CI.

- The GitHub mirror is **read-only**. PRs opened against it are
  rejected; contributors are redirected to GitLab. The repo
  description says so.
- GitHub Actions do **not** duplicate GitLab CI. They run only:
  - CodeQL (SAST native to GitHub, not easily runnable elsewhere)
  - OSSF Scorecard (scores GitHub-specific signals: branch
    protection, signed commits, Dependabot, etc.)
- Dependabot is **disabled** — Renovate on GitLab is the source of
  truth for dependency bumps.
- GitHub Releases are **not auto-populated**. `release-please` on
  GitLab owns the CHANGELOG.

### Rule 3 — Jenkinsfile is inert, not maintained weekly.

- The `Jenkinsfile` is checked in, kept roughly in sync with the
  GitLab stages that have a sensible Jenkins equivalent, but **not
  exercised by any automated pipeline**.
- It is intentionally NOT run — that would duplicate CI without
  adding signal.
- When someone adopts this project into a Jenkins shop, they take
  the `Jenkinsfile`, provision the credentials in Jenkins, and run
  it. See [`docs/ops/jenkins.md`](jenkins.md) for the full story.
- When the GitLab pipeline gets a new stage, the Jenkinsfile may lag
  by weeks. That's acceptable: the Jenkinsfile is a **parity
  demonstrator**, not a canary.

## Why this split (and not "one CI everywhere")

### Option rejected: GitLab only

Cost: loses GitHub visibility. Recruiters and OSS users who start on
GitHub never find the project.

### Option rejected: GitHub only

Cost: loses local runner pattern (ADR-0004, zero SaaS quota), loses
`release-please` native integration, loses GitLab's Container Registry,
loses the merge-request workflow the team is already wired into.

### Option rejected: Full CI on both

Cost: **every push runs twice**, results have to be reconciled, cost
surface doubles, drift between the two becomes an operational cost.
The team ends up saying "it's green on one but red on the other, let
me debug which one". That's the opposite of CI hygiene.

### Option rejected: Jenkins-only, no GitLab, no GitHub

Cost: Jenkins on a home lab + external exposure + SSL cert + plugin
auto-update + agent management. Industrial relevance for a team
already locked into Jenkins, but massive overhead for a personal
portfolio. The Jenkinsfile solves the "can this adopt Jenkins?"
question without any of the hosting burden.

## Adopted: canonical + narrow mirror + inert parity

- **Canonical**: GitLab runs the real CI.
- **Mirror with narrow CI**: GitHub gets CodeQL + Scorecard only.
  Visibility without duplication.
- **Inert parity**: Jenkinsfile proves portability without running.

Every piece is there for a reason. Nothing is there "just in case".

## What happens on each event

| Event | Canonical (GitLab) | Mirror (GitHub) | Parity (Jenkins) |
|---|---|---|---|
| Push on `dev` | full pipeline runs | nothing (not yet mirrored) | untouched |
| MR opened | full pipeline runs | nothing | untouched |
| MR merged to `main` | full pipeline runs | `github-mirror` job pushes main to GitHub → CodeQL + Scorecard trigger | untouched |
| Weekly schedule | Renovate opens bump MRs on GitLab | CodeQL + Scorecard scan (detects new CVEs in rule set) | untouched |
| Tag `v1.x` on GitLab | release-please generates CHANGELOG | mirror job pushes tag to GitHub | untouched |
| Adoption in Jenkins shop | untouched | untouched | team runs it in their Jenkins; see `jenkins.md` |

## When to break these rules

- **GitLab goes down permanently**: promote the GitHub mirror to
  canonical. Migration is documented in [`docs/ops/github-mirror.md`](github-mirror.md).
- **GitHub Actions free tier changes to force duplication**: reassess
  whether the visibility benefit still outweighs the complexity.
- **A real Jenkins-hosted demo is requested**: flip the Jenkinsfile
  from inert parity to live pipeline, add the missing stages (k8s,
  terraform), and explicitly note the transition here.

Each of these would be an ADR, not an impulse edit.

## References

- [`docs/ops/github-mirror.md`](github-mirror.md) — mirror setup,
  deploy key rotation, failure semantics.
- [`docs/ops/jenkins.md`](jenkins.md) — Jenkinsfile parity details,
  local test, adoption checklist.
- [`docs/ops/cost-control.md`](cost-control.md) — budget alert (CI
  runs don't bill but the cluster they deploy to does).
- [`.gitlab-ci.yml`](../../.gitlab-ci.yml) — the real pipeline.
- [`.github/workflows/`](../../.github/workflows/) — the narrow GitHub
  one.
- [`Jenkinsfile`](../../Jenkinsfile) — the parity demonstrator.
