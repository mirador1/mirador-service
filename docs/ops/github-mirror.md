# GitHub mirror — why it exists and what it does

## Why there is a GitHub mirror at all

The canonical repo is on **GitLab** (`gitlab.com/mirador1/mirador-service`
and `.../mirador-ui`). That's where the CI pipeline, the merge-request
workflow, the Container Registry, Renovate bot and release-please live.

Most recruiters browse **GitHub**, not GitLab. A project invisible on
GitHub is missing an audience at effectively zero cost to reach.

The fix: a read-only GitHub **mirror** at
[github.com/mirador1/mirador-service](https://github.com/mirador1/mirador-service)
and [github.com/mirador1/mirador-ui](https://github.com/mirador1/mirador-ui).
It reflects `main` + tags on every push. **It is not a fork** — GitHub
cannot host MRs against it; contributors are told to open MRs on
GitLab.

## Why there is a GitHub CI at all (if GitLab already has one)

GitLab CI already runs the full test chain (Testcontainers, kind-in-CI,
Sonar, SBOM, cosign, PIT, OWASP Dep-Check, Semgrep, Trivy, Grype, etc.).
Duplicating that on GitHub would be:

- **Redundant**: same tests run twice, same results produced twice.
- **Expensive in quota**: GitHub Actions free tier is 2 000 min/month;
  a full CI pass would burn it in weeks.
- **Fragile**: two CIs drifting over time = "it's green on GitLab but
  red on GitHub, which one do I trust?".

The GitHub workflows are deliberately **narrow**. They do ONLY what
GitHub provides natively that GitLab can't match:

| Workflow | What it runs | Why it's here, not on GitLab |
|---|---|---|
| `codeql.yml` | GitHub's native SAST. Java for the service, JavaScript/TypeScript for the UI. | CodeQL lives behind GitHub's query engine; it's not trivially runnable outside GitHub Actions. Complements Semgrep (different rule set, different false-negative profile). Badge is broadly recognised by reviewers. |
| `scorecard.yml` | OSSF Scorecard — 20-point check covering branch protection, signed commits, SBOM, CodeQL, Dependabot, fuzzing, pinned dependencies, etc. | The data source is the GitHub repo (branch rules, workflow files). A GitLab run would produce a meaningless score because it can't see GitHub-specific settings. Weekly schedule. |

**That's all.** No unit tests, no Docker build, no Maven, no npm. If a
check belongs on the canonical repo's CI, it stays on GitLab.

## What the GitHub repo does NOT do

- **Merge pull requests.** The GitHub mirror accepts zero contribution
  PRs. The repo description points contributors back to GitLab.
- **Publish Docker images.** Artifact Registry on GCP is the canonical
  registry (see ADR-0016 references in mirador-service). `ghcr.io`
  could be a future alternative but isn't on the roadmap.
- **Release notes.** `release-please` on GitLab produces the
  CHANGELOG and tags. GitHub Releases can be populated post-hoc if we
  ever need a GitHub-native download page, but it's not automatic.
- **Dependency updates.** Renovate on GitLab handles this. Dependabot
  on GitHub is **disabled** (see `.github/dependabot.yml` with
  `enabled: false`) to avoid two bots opening conflicting bumps.

## Sync mechanism

The mirror push happens **locally** via `bin/ship.sh --wait`. The
developer's authenticated `gh` CLI pushes to GitHub as part of the
ship workflow. No GitLab CI job is involved — by choice.

### Why not a CI deploy key

We tried. GitHub **free organisations disable deploy keys by
default** (anti-abuse policy — surfaces as "Deploy keys are
disabled for this repository" on `POST /repos/{owner}/{repo}/keys`).
Alternatives considered:

| Option | Cost |
|---|---|
| Upgrade org to Team plan | $4/user/month — blows the €2/month project budget |
| Fine-grained PAT | Works but 12-month expiration + user-bound identity to rotate |
| Machine user account | An extra GitHub login to maintain |
| **Local `bin/ship.sh --wait` push** | Zero identity to maintain, mirror runs when a release ships |

The local-push option wins because:

- Mirror doesn't need to be up-to-the-minute — recruiter-facing
  visibility is measured in hours, not seconds.
- `ship.sh --wait` already polls until merge; tacking the mirror
  push on at the same time adds <5 s.
- No CI credential to rotate, no PAT expiring in 12 months, no
  deploy key to revoke on staff turnover.

### What to run

```bash
# Full workflow — commit, MR, wait for merge, sync dev, mirror push
bin/ship.sh --wait

# Mirror only (catch-up after a manual merge)
git clone --mirror https://gitlab.com/mirador1/mirador-service.git /tmp/m.git
(cd /tmp/m.git && git push --mirror https://github.com/mirador1/mirador-service.git)
rm -rf /tmp/m.git
```

### When a CI-side mirror would re-earn its place

- Org plan upgrades to Team → deploy keys unlocked, SSH-key pattern
  comes back (the previous implementation lives in git history).
- Release cadence jumps to "several per day" → manual push becomes
  friction, CI wins.
- Mirror must stay current even when the maintainer is offline.

## Failure semantics

- **GitHub outage** during a push: the `github-mirror` job has
  `allow_failure: true`. GitLab CI still reports green even if GitHub
  is unreachable, because the canonical repo is GitLab.
- **Key rotated but not yet in GitLab**: same — `allow_failure: true`
  means the pipeline proceeds, `mirador-doctor` or a manual
  `git ls-remote` check surfaces the drift.
- **GitHub CI failing** (e.g. CodeQL false positive): doesn't affect
  the GitLab pipeline. Fix is opened on GitLab, lands, then the next
  mirror push propagates the fix to GitHub.

## Verify the mirror is in sync

```bash
gitlab=$(git ls-remote https://gitlab.com/mirador1/mirador-service main | awk '{print $1}')
github=$(git ls-remote https://github.com/mirador1/mirador-service main | awk '{print $1}')
[ "$gitlab" = "$github" ] && echo "✅ in sync" || echo "❌ drift: GitLab=$gitlab GitHub=$github"
```

This is one of the checks in `bin/mirador-doctor` (extend when a drift
is ever observed).

## If you ever need to disable the mirror

```bash
# Disable the CI job (temporary):
glab variable delete GITHUB_MIRROR_SSH_KEY --scope=main

# Destroy the GitHub repos (permanent, keeps the GitLab source):
gh repo delete Beennnn/mirador-service --yes
gh repo delete Beennnn/mirador-ui --yes
```

The disable path is deliberately cheap. If the GitHub mirror stops
earning its keep, removing it costs 30 seconds and loses nothing of
value.
