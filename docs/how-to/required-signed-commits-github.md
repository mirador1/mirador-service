# Re-enable `required_signatures` on GitHub `main`

**Why this is needed**: `required_signatures` was enabled on the GitHub
mirror's `main` branch then disabled during the Auth0 work session
(unsigned helper commits couldn't push). Now that local SSH-signing
setup is documented (`bin/dev/setup-signed-commits.sh`), re-enable so
every future merge into main is verifiably signed by the author.

This is **task S2** in TASKS.md.

## Pre-requisite: local signing must work first

Run `bin/dev/setup-signed-commits.sh` (or `bin/dev/setup-signed-commits.sh
--dry-run` to preview), confirm with a test signed commit:

```bash
git commit --allow-empty -m "test signing"
git log --show-signature -1
# → expect a "Good \"git\" signature" line
```

Push that commit to a feature branch on GitHub; visit the commit page in
the GitHub UI and confirm the green **Verified** badge appears next to
your name.

If those steps don't work, **do NOT proceed** — re-enabling
`required_signatures` would block your own pushes and you'd lock yourself
out of the repo. Fix the local signing first.

## Option A — GitHub web UI (recommended for first-time setup)

1. Open: <https://github.com/mirador1/mirador-service/settings/branches>
   (or `mirador-ui` for the UI repo).
2. Find the **Branch protection rules** section. There should be an
   existing rule for `main` (created during stable-v1.0.5 hardening).
3. Click **Edit** next to the `main` rule.
4. Scroll to **Protect matching branches** → check **Require signed
   commits**.
5. Click **Save changes** (bottom of the page).
6. Repeat for the UI repo.

Test by trying to push an unsigned commit — should be rejected with
"protected branch hook declined: signing required".

## Option B — GitHub REST API (scriptable)

If you have `gh` authenticated with admin scope on the repo:

```bash
# svc
gh api repos/mirador1/mirador-service/branches/main/protection/required_signatures \
  --method POST

# UI
gh api repos/mirador1/mirador-ui/branches/main/protection/required_signatures \
  --method POST
```

Verify with:

```bash
gh api repos/mirador1/mirador-service/branches/main/protection/required_signatures \
  | jq .enabled
# → true
```

## Reverting if it breaks something

Two ways to disable, fast:

```bash
# API (immediate)
gh api repos/mirador1/mirador-service/branches/main/protection/required_signatures \
  --method DELETE

# Or UI: Settings → Branches → Edit main rule → uncheck "Require signed commits"
```

If a single bad commit is blocking, you can also override per-push with
`git push --no-verify` only if `commit.gpgsign=false` is set globally
AND the branch protection rule is temporarily disabled.

## Cross-references

- `bin/dev/setup-signed-commits.sh` — local SSH-sign setup (run first)
- TASKS.md → "S2 — signed-commits hardening"
- GitHub docs:
  <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-protected-branches/about-protected-branches#require-signed-commits>
- GitLab equivalent (if needed later):
  <https://docs.gitlab.com/ee/user/project/repository/signed_commits/>
