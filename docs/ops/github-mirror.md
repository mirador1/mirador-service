# GitHub mirror — why it exists and what it does

## Why there is a GitHub mirror at all

The canonical repo is on **GitLab** (`gitlab.com/mirador1/mirador-service`
and `.../mirador-ui`). That's where the CI pipeline, the merge-request
workflow, the Container Registry, Renovate bot and release-please live.

Most recruiters browse **GitHub**, not GitLab. A project invisible on
GitHub is missing an audience at effectively zero cost to reach.

The fix: a read-only GitHub **mirror** at
[github.com/Beennnn/mirador-service](https://github.com/Beennnn/mirador-service)
and [github.com/Beennnn/mirador-ui](https://github.com/Beennnn/mirador-ui).
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

The GitLab CI has a `github-mirror` job that runs on every push to
`main`. It does `git push --mirror` to the GitHub repo using a
**deploy key** (SSH keypair) scoped to these two repos only.

- **Public key** on GitHub: repo → Settings → Deploy keys, **write
  access enabled**.
- **Private key** on GitLab: Settings → CI/CD → Variables →
  `GITHUB_MIRROR_SSH_KEY`, **masked**, **protected** (only exposed
  to jobs running on the protected `main` branch).

The deploy key beats a Personal Access Token for this use case
because:

- **No expiration** — PATs cap at 12 months and must be rotated; a
  deploy key stays valid until the public part is revoked.
- **Scope is one repo per key** — compromise blast radius is clamped.
- **Not tied to a user identity** — the key can't do anything on
  the owner's other projects.

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
github=$(git ls-remote https://github.com/Beennnn/mirador-service main | awk '{print $1}')
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
