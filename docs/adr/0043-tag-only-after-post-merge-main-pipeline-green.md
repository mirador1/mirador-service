# ADR-0043 — Tag stable-vX.Y.Z only after the post-merge main pipeline is green

- Status: Accepted
- Date: 2026-04-21
- Deciders: @benoit.besson
- Related: CLAUDE.md "Tag every green stability checkpoint, never tag on red"

## Context

The project tags every provably-stable state as `stable-vX.Y.Z` so
the team can roll back to a known-good commit. Early in this
session the tag dance was:

1. MR auto-merges (MR pipeline green).
2. `git tag -a stable-v1.0.X origin/main` immediately after the
   merge lands on main.
3. Push tag to `origin` + `github` mirror.
4. Post-merge main pipeline runs 5–10 min later. "If it goes red,
   I'll delete the tag and re-tag on the fix."

The recovery plan in step 4 is the problem. It relies on noticing
the red pipeline, remembering the plan, and executing the
delete+retag without getting interrupted by the next task. In
practice the recovery was forgotten twice in this session:

- **stable-v1.0.7** was tagged right after `!119` merged. Main
  pipeline #570 then failed (sonar-analysis MR regression —
  ADR-0041). Tag moved after the fix.
- The same session produced **main pipeline #592 with 0 jobs**
  after `!123` merged (terraform-apply `needs: terraform-plan`
  without `optional: true`). Had I already tagged stable-v1.0.8
  on that commit, the tag would point at a state whose main
  pipeline never actually validated anything.

Each near-miss produced a tag that (a) would have labelled a red
commit as "stable" and (b) required manual intervention (delete
+ retag, push --force) to recover. The "move the tag" recovery
is itself a supply-chain smell — consumers who fetched the tag
in the 5-min window saw a different commit than consumers after
the recovery.

## Decision

**Do not tag until the post-merge main pipeline reports `success`
on the merge-commit SHA.** Replace the "tag-then-maybe-recover"
dance with "wait-then-tag".

Operationally:

1. MR auto-merge armed.
2. When the MR merges, GitLab launches a new pipeline on `main`
   with the squash commit as SHA.
3. Monitor that specific pipeline via a watcher that checks
   `(state == 'merged') AND (main.sha matches squash SHA) AND
   (main.status == 'success')`.
4. Only then: tag, push to both remotes.

## Alternatives considered

### A) Keep the "tag immediately, recover if red" flow

**Rejected** because the recovery requires human attention at
exactly the wrong moment (during the next task), the "tag on red"
window is visible to anyone who `git fetch --tags` during the
gap, and the delete+retag leaves reflog churn that makes
post-mortems harder.

### B) Tag on every MR pipeline green (before merge)

**Rejected** because the MR pipeline does NOT run the full main-
branch ruleset (deploy stages, scheduled-only jobs). A change
that passes the MR pipeline can still break main. This session's
`!123` went through that exact failure shape: MR pipeline
green, main pipeline 0-jobs fail.

### C) Wait for post-merge main pipeline green (accepted)

Waits 5–10 min extra after each MR lands, but the tag now
points at a commit whose FULL main-branch pipeline verifiably
passed. No recovery dance, no tag migration.

## Consequences

**Positive**:
- `stable-vX.Y.Z` tags are guaranteed to point at a commit whose
  main pipeline was green. Consumers can trust the tag without
  cross-checking the pipeline.
- No delete+retag churn — tags are immutable once pushed.
- Forces the monitoring script to capture the merge SHA and
  watch THAT specific pipeline, catching the "0-jobs pipeline"
  failure shape that simpler "wait for any green main" would
  miss (that was `!123` / `#592`).

**Negative**:
- +5–10 min between MR merge and tag push. Acceptable since
  tags are for stability, not for release velocity.
- Requires a more careful watcher script (merge SHA match,
  not just "latest main pipeline green"). The SHA-aligned
  watcher pattern is documented in the CLAUDE.md operational
  note.
- If the post-merge pipeline never runs (workflow:rules
  excludes the changed paths), no tag — catch this by keeping
  the workflow allowlist broad enough that every merged change
  triggers a pipeline (see `bin/**`, `.github/**`,
  `.spectral.yaml`, `CLAUDE.md` in the allowlist since
  2026-04-21).

## Operational pattern (SHA-aligned watcher)

```bash
last_mr=""; last_main=""; mr_merged_sha=""
while true; do
  mr_json=$(glab mr view --repo "$PROJECT" "$MR_IID" --output json 2>/dev/null)
  mr=$(echo "$mr_json" | jq -r '"\(.state)|\(.head_pipeline.status // "none")"')
  squash_sha=$(echo "$mr_json" | jq -r '.squash_commit_sha // .merge_commit_sha // ""')
  main_top=$(glab api "projects/$PROJECT_ENC/pipelines?ref=main&per_page=1" \
    | jq -r '"\(.[0].iid // "?")|\(.[0].status // "none")|\(.[0].sha // "")"')
  main_iid="${main_top%%|*}"; rest="${main_top#*|}"
  main_status="${rest%%|*}"; main_sha="${rest#*|}"

  if [[ "$mr" == merged* && -n "$squash_sha" && -z "$mr_merged_sha" ]]; then
    mr_merged_sha="$squash_sha"
    echo "$(date '+%H:%M:%S') MR merged as ${squash_sha:0:8}; waiting for main on this SHA"
  fi

  # Green only when the main pipeline on the MERGE SHA succeeded.
  if [[ -n "$mr_merged_sha" && "$main_sha" == "$mr_merged_sha"* \
        && "$main_status" == "success" ]]; then
    echo "BOTH merged + post-merge main green — exit"; exit 0
  fi

  case "$mr" in *failed*) exit 1 ;; esac
  if [[ -n "$mr_merged_sha" && "$main_sha" == "$mr_merged_sha"* \
        && "$main_status" == "failed" ]]; then
    exit 1
  fi
  sleep 30
done
```

Key distinction: `$main_sha == $mr_merged_sha` check, NOT "any
main pipeline green". Without it, the watcher can exit on an
unrelated older green pipeline.

## Revisit criteria

- GitLab adds a "merge-when-pipeline-succeeds AND tag on success"
  atomic primitive → drop the external watcher.
- Post-merge main pipelines are routinely fast (<2 min) → the
  wait cost becomes trivial; nothing changes but the waiting
  is less annoying.

## References

- `~/.claude/CLAUDE.md` → "Tag every green stability checkpoint,
  never tag on red" — the enforced rule.
- Project `CLAUDE.md` files (`mirador-service/CLAUDE.md` + 
  `mirador-ui/CLAUDE.md`) — mirror rule at the repo level.
- Session that produced this ADR: the `stable-v1.0.8` window,
  where the rule saved stable-v1.0.8 from being tagged on main
  pipeline #592 (0 jobs, post-`!123`).
- ADR-0041 — scope-out pattern; its emergence required the same
  post-merge-main-green discipline to avoid silently shipping
  pipeline regressions.
