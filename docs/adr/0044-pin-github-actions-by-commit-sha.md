# ADR-0044 — Pin GitHub Actions by full commit SHA (not tag)

- Status: Accepted
- Date: 2026-04-21
- Deciders: @benoit.besson
- Related: CLAUDE.md "Pin every upstream reference. No floating tags."

## Context

The `.github/workflows/scorecard.yml` + `codeql.yml` workflows
originally referenced actions by tag:

```yaml
uses: actions/checkout@v4
uses: ossf/scorecard-action@v2.4.0
uses: github/codeql-action/init@v3
```

Two problems with this:

### 1. Tags are mutable — supply-chain risk

A tag is a moving pointer. The upstream maintainer can (accidentally
or under compromise) re-point `v4` onto a new commit anytime. Every
subsequent CI run fetches the new code. SHA pinning locks the action
to one specific commit — the upstream can't swap it out.

The **tj-actions/changed-files** incident in March 2025 made this
non-theoretical: a compromised token re-pointed `v44` and `v45` tags
to a malicious commit that dumped CI secrets to Actions logs. Every
project using `@v44`/`@v45` exfiltrated their own secrets on the
next CI run. SHA-pinned consumers were unaffected.

### 2. Sonar `githubactions:S7637`

SonarCloud flags `uses: X@tag` as a security hotspot on the
`githubactions:S7637` rule — "Using external GitHub actions and
workflows without a commit reference is security-sensitive".
This drove `new_security_rating = 3` on the project's quality
gate until the fix landed.

### 3. Annotated-tag SHA gotcha (incident)

The first SHA-pinning attempt used SHAs returned by:

```bash
git ls-remote --tags --refs https://github.com/ossf/scorecard-action.git v2.4.0
# → ff5dd8929f96a8a4dc67d13f32b8c75057829621    refs/tags/v2.4.0
```

For `ossf/scorecard-action@v2.4.0` (an **annotated tag**) this SHA
is the **tag-object SHA**, NOT the commit SHA it points to.
GitHub's action resolver accepts both (tag-ref resolution at
runtime), but Scorecard's signing webapp explicitly rejects
tag-object SHAs:

```
workflow verification failed: imposter commit:
ff5dd8929f96a8a4dc67d13f32b8c75057829621 does not belong to
ossf/scorecard-action, see https://github.com/ossf/scorecard-action#workflow-restrictions
```

Same trap on `github/codeql-action@v3` (used 3× across the two
workflows).

## Decision

**Every `uses:` in `.github/workflows/**/*.yml` pins the action
to its FULL COMMIT SHA with a trailing `# vX.Y.Z` comment. The
SHA must be the commit, not the tag object.**

### Dereferencing annotated tags

Two-step resolution:

1. `git ls-remote --tags` gives the SHA that `v2.4.0` points at.
2. Ask the GitHub API whether that SHA is a commit or a tag:
   ```bash
   curl -s "https://api.github.com/repos/<org>/<repo>/git/commits/<sha>"
   # → HTTP 200  → SHA IS a commit, use as-is
   # → HTTP 404  → SHA is a tag-object, deref:
   curl -s "https://api.github.com/repos/<org>/<repo>/git/tags/<sha>" \
     | jq '.object.sha'
   # → the commit SHA
   ```

Confirmed classification as of 2026-04-21:

| Action | Type | Commit SHA (used) |
|---|---|---|
| `actions/checkout@v4` | lightweight | `34e114876b0b11c390a56381ad16ebd13914f8d5` |
| `actions/setup-java@v4` | lightweight | `c1e323688fd81a25caa38c78aa6df2d33d3e20d9` |
| `actions/upload-artifact@v4` | lightweight | `ea165f8d65b6e75b540449e92b4886f43607fa02` |
| `ossf/scorecard-action@v2.4.0` | **annotated** | `62b2cac7ed8198b15735ed49ab1e5cf35480ba46` |
| `github/codeql-action@v3` | **annotated** | `ce64ddcb0d8d890d2df4a9d1c04ff297367dea2a` |

### Trailing comment format

```yaml
uses: owner/name@<40-char-commit-sha> # vX.Y.Z
```

Space before `#`, tag on the same line. Renovate's `github-actions`
manager parses that exact shape as the `currentValue` — digest
updates land via Renovate's `digest` update-type (auto-merged
per `renovate.json` line ~30).

## Alternatives considered

### A) Keep tag-pinned actions

**Rejected.** Supply-chain attack surface demonstrated by the
tj-actions incident. SonarCloud `githubactions:S7637` flags every
tag-pinned `uses:` as a security hotspot; keeping them would mean
either accepting a permanent red on `new_security_rating` or
marking every hotspot "acknowledged" indefinitely.

### B) Use the `major` tag (e.g. `@v4`) with Dependabot/Renovate auto-bump

**Partially adopted.** Major-tag pin + auto-bump doesn't close
the mutable-tag attack window — the auto-bump PR only runs AFTER
the malicious code has already executed in a previous scheduled
pipeline. SHA pinning + Renovate `digest` updater gives both
safety AND auto-maintained currency.

### C) Use only GitHub-maintained actions (actions/* and github/*)

**Partially adopted** de facto (3 of 5 actions are first-party).
The remaining `ossf/scorecard-action` is unavoidable — it IS the
Scorecard analyzer. Still SHA-pinned for the same reason.

## Consequences

**Positive**:
- Supply-chain attack surface against our workflows reduced to
  zero for the pinned SHA. A compromised upstream tag can't push
  new code into our CI.
- `githubactions:S7637` cleared on SonarCloud — quality gate
  driver closed (`new_security_rating` went to 1).
- Renovate `digest` update type tracks upstream commits per
  action; each bump lands as a small reviewable PR.

**Negative**:
- Annotated-tag gotcha requires an extra API call to resolve
  correctly. Missing this step ships an "imposter commit" that
  Scorecard (and any strict consumer) rejects at runtime.
  Inline comment in `scorecard.yml` documents the trap so the
  next maintainer doesn't repeat it.
- Major upgrades (`v3` → `v4`) need a manual SHA re-resolve,
  not just a YAML find/replace — Renovate handles minor/patch
  but majors are gated per `renovate.json` policy.

## Operational checklist when adding a new `uses:` reference

1. Fetch the tag object:
   ```bash
   SHA=$(git ls-remote --tags https://github.com/<org>/<repo>.git <TAG> | awk '{print $1}')
   ```
2. Verify it's a commit (not a tag object):
   ```bash
   curl -sf "https://api.github.com/repos/<org>/<repo>/git/commits/$SHA" > /dev/null \
     || SHA=$(curl -s "https://api.github.com/repos/<org>/<repo>/git/tags/$SHA" \
              | jq -r '.object.sha')
   ```
3. Write `uses: <org>/<name>@$SHA # <TAG>` with the trailing
   comment.
4. Commit + push + verify the Scorecard workflow run doesn't
   produce "imposter commit" — see run 24717901514 for the
   shape of the error if you get it wrong.

## Revisit criteria

- GitHub ships a first-class "immutable tag" primitive
  (signed tag references that can't be re-pointed) → may
  relax SHA pinning in favour of signed tags.
- Scorecard relaxes the imposter-commit check → no operational
  change needed, but the deref step becomes less critical.
- `actions/<X>` ship per-release SLSA v3 provenance + a
  Mirador-side verify step → tighten the supply chain further.

## References

- `.github/workflows/scorecard.yml` + `.github/workflows/codeql.yml`
  — current SHA-pinned state.
- Scorecard run 24717901514 — the "imposter commit" rejection
  that exposed the annotated-tag gotcha.
- tj-actions/changed-files compromise: https://github.com/tj-actions/changed-files/issues/2463
  (March 2025 incident — canonical reference for the threat
  model).
- ~/.claude/CLAUDE.md → "Pin every upstream reference" — general
  rule; this ADR applies it specifically to GitHub Actions.
- `renovate.json` — `packageRules` → `matchUpdateTypes: [patch,
  pin, digest]` auto-merge.
