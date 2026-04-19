# GitHub mirror — make the project visible where recruiters look

Most recruiters browse GitHub, not GitLab. Mirroring this project
read-only to GitHub closes that visibility gap at essentially zero
infrastructure cost. The canonical repo stays on GitLab (where the CI
+ Container Registry + merge-request workflow live); GitHub is a
passive reflection.

## Setup (one-time, ~10 min)

### 1. Create a public empty GitHub repo

```
https://github.com/new
  → name: mirador-service
  → visibility: Public
  → do NOT initialise with README / LICENSE / .gitignore
```

Repeat for `mirador-ui`.

### 2. Generate a fine-grained PAT

`https://github.com/settings/personal-access-tokens/new`

- **Resource owner**: your account
- **Repository access**: "Only select repositories" → `mirador-service`
  and `mirador-ui`
- **Permissions** → `Repository permissions` → **Contents: Read and
  write**. Nothing else.
- **Expiration**: 12 months. Renovate-style annual rotation.

Save the token as a **masked protected** CI/CD variable in each GitLab
project:

```
GitLab → Settings → CI/CD → Variables
  key:    GITHUB_MIRROR_TOKEN
  value:  <the PAT>
  type:   Variable
  flags:  [✓] Protected  [✓] Masked  [✗] Expand
  scope:  main
```

Protected means the variable is only exposed to jobs running on
protected branches (main). Masked redacts it from job logs.

### 3. Add the CI job

Append to `.gitlab-ci.yml` at the bottom (one stage, one job):

```yaml
# ── stage: mirror ────────────────────────────────────────────────────
# Read-only reflection of the main branch to GitHub. Runs only on main
# push after the standard test chain. allow_failure:true because a
# GitHub outage must not block the GitLab CI pipeline.
stages:
  - mirror

github-mirror:
  stage: mirror
  image: alpine/git:2.45.2
  tags:
    - macbook-local
  rules:
    - if: '$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH && $GITHUB_MIRROR_TOKEN'
  needs: []   # independent of the test chain
  script:
    # Clone in --mirror mode so the push replicates refs + tags + branches
    - git clone --mirror "$CI_REPOSITORY_URL" repo.git
    - cd repo.git
    - git remote set-url --push origin "https://oauth2:${GITHUB_MIRROR_TOKEN}@github.com/$GITHUB_OWNER/$CI_PROJECT_NAME.git"
    - git push --mirror
  variables:
    # Override if your GitHub org/user differs from the default
    GITHUB_OWNER: benoit-besson
  allow_failure: true
  timeout: 2 minutes
```

### 4. Rotate the PAT yearly

The 12-month expiration forces a deliberate refresh. When the mirror
job starts failing with `403 Password authentication is not
supported`, regenerate the PAT and replace the GitLab variable.

## Why not `github.com/<owner>/mirror/fork`?

GitHub forks are private to a source repo you own; we can't fork from
a GitLab source. The only way to mirror cross-platform is a push from
the canonical repo.

## Why not GitHub Actions → GitLab pull?

Works but inverts the direction: CI state lives on the GitLab side,
so the push direction aligns better. Pull from GitHub Actions would
also need a GitLab PAT in a GitHub secret, flipping the trust
boundary.

## Verify it's working

```bash
# on GitHub
open https://github.com/benoit-besson/mirador-service
open https://github.com/benoit-besson/mirador-ui

# compare HEAD SHAs
gitlab=$(git ls-remote https://gitlab.com/mirador1/mirador-service main | awk '{print $1}')
github=$(git ls-remote https://github.com/benoit-besson/mirador-service main | awk '{print $1}')
[ "$gitlab" = "$github" ] && echo "✅ in sync" || echo "❌ out of sync"
```

## Security notes

- The PAT has **write access** to the GitHub mirror only. It cannot
  touch other repos, cannot create new repos, cannot delete.
- If the token leaks, revoke it at
  `https://github.com/settings/personal-access-tokens` and rotate.
- Treat the GitHub mirror as **untrusted display-only** — never
  merge PRs opened against it; direct contributors to GitLab.
- Add a `NOTICE` line to the GitHub repo description so visitors
  know where to contribute: "Read-only mirror of
  gitlab.com/mirador1 — open MRs there".
