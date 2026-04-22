# ADR-0050: CI YAML modularisation — `ci/includes/*.yml` per concern

- **Status**: Accepted (svc implemented 2026-04-22: 2619 → 173 LOC parent + 9 includes; UI pending in Phase B-4)
- **Date**: 2026-04-22
- **Deciders**: Mirador maintainers
- **Related**: [ADR-0041](0041-ci-hygiene-honest-green-discipline.md) (CI
  hygiene), [ADR-0049](0049-ci-shields-with-dated-exit-tickets.md), `~/.claude/CLAUDE.md` → "File length hygiene"

## Context

Two `.gitlab-ci.yml` files in the project both blew past the 1500-line
"split NOW" tier from the new file-length-hygiene rule:

- svc `.gitlab-ci.yml` — **2 619 lines**, 40+ jobs across 9 stages
  (pages, lint, test, integration, k8s, package, compat, native, sonar,
  reports, infra, deploy)
- UI `.gitlab-ci.yml` — **1 067 lines**, 12 jobs across 7 stages

Both files exhibit the same pathology: scrolling-with-grep is the only
way to find a job, MR diffs touch unrelated job blocks, "where do I add
the new shield comment?" requires reading dozens of unrelated jobs first.

Stability-check.sh's `section_file_length` (added 2026-04-21) flags both
as BLOCK and they sit in its allowlist with explicit Phase B-2/B-4 exit
tickets.

## Decision

Both repos move to a **per-concern include split**. The orchestration file
becomes a thin manifest (~150 lines svc / ~100 lines UI) listing the
includes in pipeline-execution order; each concern lives in its own
`ci/includes/<concern>.yml`.

### svc layout

```
.gitlab-ci.yml                             # workflow + stages + variables + includes (~150 lines)
ci/includes/
  ├── lint.yml                             # hadolint, openapi-lint, promtool-check-rules
  ├── test.yml                             # unit-test, integration-test, integration-test:keycloak, compat-*
  ├── security.yml                         # sast (semgrep), secret-scan, owasp-dependency-check, grype:scan
  ├── k8s.yml                              # test:k8s-apply, test:k8s-apply-prom
  ├── quality.yml                          # sonar-analysis, code-quality, mutation-test
  ├── package.yml                          # build-jar, docker-build, cosign:sign, cosign:verify, sbom, trivy:scan, dockle
  ├── native.yml                           # build-native (scheduled only)
  ├── deploy.yml                           # deploy:eks, deploy:aks, smoke-test, load-test:nightly
  └── release.yml                          # release-please, GitLab Pages
```

Estimated post-split sizes per file: 200-400 lines max, with most around
250.

### UI layout

```
.gitlab-ci.yml                             # ~100 lines
ci/includes/
  ├── validate.yml                         # typecheck, lint:format, lint:eslint, openapi:types-drift
  ├── test.yml                             # unit-tests, unit-tests:node20
  ├── build.yml                            # build:production
  ├── e2e.yml                              # e2e:kind, all Playwright variants
  ├── quality.yml                          # sonarcloud, bundle-size-check, lint:circular-deps
  ├── security.yml                         # secret-scan, security:audit
  └── docker.yml                           # docker-build, trivy, sbom, grype, dockle, cosign
```

### Conventions

1. **Include order matches stage execution order** — the `.gitlab-ci.yml`
   `include:` block reads top-to-bottom in the same order jobs run.
   Anchors / `!reference` definitions must be in includes loaded BEFORE
   their consumers (GitLab loads includes in the order listed).
2. **Variables stay in the orchestration file** — `MAVEN_OPTS`,
   `TESTCONTAINERS_RYUK_DISABLED`, `NODE_VERSION`, etc. Centralised so
   one file hosts the cross-cutting environment.
3. **Cache definitions stay in orchestration too** — `cache:` blocks
   referenced via `extends:` from multiple jobs need a single home.
4. **Workflow rules stay in orchestration** — the `workflow:rules:`
   path-filter list is per-repo policy; not split per concern.
5. **Per-include comment header** — first 10-20 lines of each include
   explain WHAT family of jobs lives there + WHY they're grouped.

### Validation gate

The split MUST be verified zero-diff at the rendered-pipeline level:

```bash
# Before split
git checkout pre-split
glab ci config > /tmp/before.yml

# After split
git checkout post-split
glab ci config > /tmp/after.yml

diff -u /tmp/before.yml /tmp/after.yml
# → MUST be empty
```

If the diff is not empty, a job was lost / duplicated / reordered. Halt
the merge until it's resolved. The stability-check.sh `section_ci_diff`
follow-up will automate this gate per-MR.

### Migration commit pattern

One commit per include extraction (9 commits svc, 7 commits UI). Each
commit:
1. Move the relevant job blocks into `ci/includes/<concern>.yml`
2. Add `- local: ci/includes/<concern>.yml` in the orchestration file
3. Run `glab ci config` diff to validate zero-diff
4. Commit message: `refactor(ci): extract <concern> jobs to ci/includes/<concern>.yml`

Atomic commits make rejecting one bad split easy via revert.

## Consequences

### Positive

- Each concern has one home: debugging a failed `test:k8s-apply` →
  open `ci/includes/k8s.yml`, not scroll 2600 lines.
- MR diffs become topical: a chaos-related change shows changes in
  `ci/includes/k8s.yml` only, not bury the diff in CI noise.
- Future ADRs / runbooks can link to specific includes (stable URLs).
- The split UNBLOCKS the file-length-hygiene "split now" gate for both
  CI files. Currently in the allowlist with Phase B-2/B-4 tickets;
  post-split, the allowlist entries delete.

### Negative

- **GitLab includes have subtle gotchas**:
  - Variable interpolation order: `variables:` from parent `.gitlab-ci.yml`
    visible in includes, but NOT vice-versa.
  - `!reference [.anchor, key]` works cross-include only if the anchor
    file is loaded first (order matters).
  - `extends:` chains across multiple includes can produce silently
    duplicated `rules:` (verified at render via `glab ci config`).
- **Slower local linting** — `glab ci lint --remote` re-fetches all
  includes every run. Mitigated by GitLab caching (~200ms typical).
- **Onboarding cost** — new contributors need to know "where does X
  live?". Mitigated by the per-include header comment + this ADR.

### Neutral

- File COUNT in `ci/includes/` grows from 0 → 9 (svc) / 0 → 7 (UI).
  Both well under the subdirectory-hygiene 10-entry threshold; no
  further sub-grouping needed.
- Total LINES across all CI files stays roughly the same (~2 600 svc).
  The win is per-file, not aggregate.

## Alternatives considered

### Alternative A — Keep monolith, add a TOC-style header

Add a 50-line header to `.gitlab-ci.yml` listing all jobs by stage with
line numbers. Pro: zero structural change, nothing breaks. Con: doesn't
solve MR-diff noise or the file-length gate; line numbers go stale.

### Alternative B — Shared template repo (`mirador-ci-templates`)

Extract common patterns (gitleaks, trivy, cosign, secret-scan) to a
3rd repo and `include:` from svc + UI. Pro: real DRY across repos.
Con: maintenance overhead of a 3rd repo for a portfolio project; cross-
repo include version pinning becomes its own headache. Defer until
multi-repo scale justifies it.

### Alternative C — Per-stage split (lint.yml, test.yml, deploy.yml only)

3-4 includes instead of 9. Pro: simpler. Con: `lint.yml` would still
mix unrelated concerns (Spectral OpenAPI lint vs hadolint vs promtool —
different domains, different debug paths). The 9-file plan groups by
debug-domain, not by stage, which matches how a reviewer thinks.

## References

- `~/.claude/CLAUDE.md` → "File length hygiene"
- `docs/audit/quality-thresholds-2026-04-21.md` — Phase A audit, names
  the 2 CI files in the offenders list
- TASKS.md → Phase B-2 + Phase B-4
- [ADR-0041](0041-ci-hygiene-honest-green-discipline.md) — CI hygiene baseline
- GitLab CI `include:` docs:
  <https://docs.gitlab.com/ee/ci/yaml/includes.html>
- `glab ci config` for pre/post-split validation
