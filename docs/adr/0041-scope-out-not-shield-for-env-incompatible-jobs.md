# ADR-0041 — Scope-out, don't shield, jobs that cannot succeed in the current environment

- Status: Accepted
- Date: 2026-04-21
- Deciders: @benoit.besson
- Related: ADR-0037 (Spectral rules disabled), the "Pipelines stay green" rule in `~/.claude/CLAUDE.md`

## Context

Several CI jobs in `.gitlab-ci.yml` were carrying `allow_failure: true`
shields to hide failures that were, in fact, structural:

### Incident 1 — `sonar-analysis` on MR pipelines

SonarCloud's free tier does **not** support pull-request / merge-request
analysis. Submitting an MR-scoped analysis returns:

```
[ERROR] Project not found. Please check the 'sonar.projectKey' and
'sonar.organization' properties, the 'SONAR_TOKEN' environment variable,
or contact the project administrator to check the permissions…
```

The previous track-record audit said "7/7 success on main" — which was
correct. What it missed: the same job was failing 4-out-of-4 on MR
pipelines (MRs 109, 110, 112, 113). The `allow_failure: true` shield
was masking 100 % of MR runs.

Removing the shield surfaced the problem. Pipeline #570 (post-merge
`!113`) went fully red because the MR pipeline also ran sonar-analysis
and it failed as usual. The "main is green, shield removed" pattern
was broken because sonar-analysis never worked on MR in the first place.

### Incident 2 — `terraform-plan` without `TF_STATE_BUCKET`

`terraform-plan` ran on every MR + every main push and failed 5/5 main
+ 5/5 MR with "bucket doesn't exist". Per [ADR-0022](0022-ephemeral-demo-cluster.md)
the project deliberately does not provision a permanent GCS state
bucket. Trying to run `terraform init` against an empty backend config
fails 100 %. `allow_failure: true` hid the red square on every pipeline
view.

### Incident 3 — post-hoc pipeline reject with 0 jobs

After `terraform-plan` was scoped out via `when: never`, `terraform-apply`
had `needs: terraform-plan` without `optional: true`. GitLab refused to
*create* the whole pipeline:

```
'terraform-apply' job needs 'terraform-plan' job, but 'terraform-plan'
does not exist in the pipeline. This might be because of the only,
except, or rules keywords. To need a job that sometimes does not
exist in the pipeline, use needs:optional.
```

Caught only because the tag-on-green rule (ADR-0043) forced a second
look at the post-merge main pipeline.

## Decision

**When a CI job cannot succeed in the current environment — regardless
of the code change — scope it out via `rules:` rather than shield the
failure with `allow_failure: true`. When a dependent job references it
via `needs:`, mark the reference `optional: true`.**

Concretely:

- `sonar-analysis` rule narrowed to `$CI_COMMIT_BRANCH == $CI_DEFAULT_BRANCH`
  only — MR triggers removed. Free-tier limitation documented inline.
- `terraform-plan` rule gated on `$TF_STATE_BUCKET` being non-empty;
  otherwise `when: never`. State-bucket-unprovisioned state documented
  inline.
- `terraform-apply` has `needs: terraform-plan` with `optional: true`
  + a mirror `$TF_STATE_BUCKET` rule on its own `when: manual` gate,
  so the button is hidden when the prerequisite isn't met.

## Alternatives considered

### A) Keep `allow_failure: true`

**Rejected**: produces permanent red squares in every pipeline view,
silently tolerates real regressions (the 4 MR failures were invisible
until the shield was lifted), and trains the team to ignore red as
"normal".

### B) Flip to `allow_failure: false` and fix the underlying issue

**Rejected for the specific cases**: the underlying issue is not a
fixable code problem, it's an environment-level constraint (no paid
SonarCloud, no state bucket). Flipping to `false` without scoping
out breaks every pipeline.

### C) Scope-out via `rules:` (accepted)

Accepts that these jobs do not run in the current environment. Pipeline
view is clean (skipped, not failed). When the environment changes
(paid SonarCloud enabled, state bucket provisioned), the gate activates
automatically because the rule check resolves the other way.

## Consequences

**Positive**:
- Every pipeline view has only jobs that can actually succeed on the
  current branch / env combination. Red squares are real regressions.
- The `allow_failure: true` shield count trends down monotonically —
  easier to audit per CLAUDE.md "Pipelines stay green".
- When the environment changes (paid tier, bucket provisioned, token
  set), the gate turns on automatically — no code change needed.

**Negative**:
- Requires per-job investigation before flipping a shield. The
  audit must include MR runs, not just main runs — missing this led
  to the sonar-analysis regression in !113/#570.
- `needs: optional: true` is easy to forget — if job A is scope-out'd
  and job B has `needs: A` without `optional`, the pipeline fails
  to create at all (caught post-merge on `terraform-apply`, recovered
  by tag-on-green discipline).

## Operational checklist when scoping out a job

1. Verify the underlying reason is **environmental**, not a bug.
2. Pick the gate condition (`$TOKEN == null`, `$CI_COMMIT_BRANCH != main`,
   etc.).
3. Add `when: never` at the top of the `rules:` block for the negative
   case, keep the positive cases below.
4. **Grep for `needs:` references** to the job. For each one, add
   `optional: true`. Grep:
   ```bash
   grep -nE "needs:.*<job-name>|job:\s*<job-name>" .gitlab-ci.yml
   ```
5. Replace the `allow_failure: true` line + its dated TODO comment
   with a `# Shield removed <date> — <one-line rationale>` block
   pointing at this ADR.
6. Run `glab api ci/lint` to validate the YAML before pushing.
7. Open the MR and — per ADR-0043 — wait for the post-merge main
   pipeline GREEN on the merge SHA before tagging.

## Revisit criteria

- SonarCloud upgrades its free tier to support PR analysis → re-add
  the MR rule on `sonar-analysis`.
- GCS state bucket is provisioned + `TF_STATE_BUCKET` is set in CI
  variables → `terraform-plan` + `terraform-apply` rules activate
  automatically; no change to the YAML.
- A new job is added that has a documented external dependency — use
  the same pattern from day one, don't ship with `allow_failure: true`
  and plan to scope out later.

## References

- `.gitlab-ci.yml` — `sonar-analysis` rules block (scope-out);
  `terraform-plan` + `terraform-apply` rules blocks.
- Pipelines that revealed the pattern:
  [#570](https://gitlab.com/mirador1/mirador-service/-/pipelines/2467312321)
  (sonar fail masked by shield);
  [#592](https://gitlab.com/mirador1/mirador-service/-/pipelines/2467721661)
  (0-job creation fail, needs-not-optional).
- CLAUDE.md → "Pipelines stay green" — the enforcing rule.
- ADR-0022 — why `TF_STATE_BUCKET` is unset by design.
- ADR-0043 — tag-on-green discipline that caught the needs-not-optional
  case before it became stable-v1.0.8.
