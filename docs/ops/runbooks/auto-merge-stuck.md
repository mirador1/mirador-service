# CI pipeline auto-merge not firing

## Quick triage (30 seconds)

```bash
glab mr view <n> 2>&1 | head -20          # MR state + detailed status
glab pipeline list --per-page 3           # latest pipeline status
```

If `detailed_merge_status: mergeable` AND pipeline is `success` but the
MR hasn't merged → MWPS (merge_when_pipeline_succeeds) got disarmed.
A push to the source branch disarms it automatically.

## Likely root causes (in order of frequency)

1. **MWPS disarmed by a new push.** GitLab resets
   `merge_when_pipeline_succeeds` to `false` every time a new commit
   lands on the source branch. Re-arm manually.
2. **Pipeline actually failed** and the job has `allow_failure: false`.
   Don't blame auto-merge until you confirm the pipeline is green.
3. **MR has merge conflicts** after a main push. `detailed_merge_status`
   is `conflicts`. Rebase the source branch.
4. **Approvals required but missing.** Not used on this project but
   common elsewhere.
5. **GitLab is rate-limited** — the API returned 429 during MWPS arming.
   Rare but seen around mass Renovate sweeps.

## Commands to run

```bash
# 1. Full MR state (JSON, precise)
glab api "projects/<PROJECT-URLENCODED>/merge_requests/<N>" \
  | python3 -c "import sys,json;d=json.load(sys.stdin); \
    print('state:',d['state']); \
    print('detailed:',d['detailed_merge_status']); \
    print('mwps:',d['merge_when_pipeline_succeeds']); \
    print('pipeline:',(d.get('head_pipeline') or {}).get('status'))"

# 2. Re-arm auto-merge
glab api --method PUT \
  "projects/<PROJECT-URLENCODED>/merge_requests/<N>/merge" \
  -f merge_when_pipeline_succeeds=true \
  -f should_remove_source_branch=false \
  -f squash=true      # or false — match project convention

# 3. Squash-merge now if pipeline already succeeded
glab api --method PUT \
  "projects/<PROJECT-URLENCODED>/merge_requests/<N>/merge" \
  -f should_remove_source_branch=false \
  -f squash=true
```

## Fix that worked last time

- **MWPS disarmed after push** (most common) — re-arm with the command
  above. GitLab fires the merge as soon as the pipeline finishes.
- **Pipeline green but merge 404** — occasionally GitLab takes 15s
  after pipeline completion to flip the MR to "mergeable". Wait 30s.
- **Squash setting mismatch** — `squash=true` is our project default
  for industrial MRs (keeps `main` linear). The `glab mr merge`
  command honours whatever is set on the MR; flip via API if wrong.

## When to escalate

If the API 422s with "Branch cannot be merged" AND the MR looks
mergeable in the UI, the target branch is protected or a
required-approvals rule is in effect. Check
`https://gitlab.com/<PROJECT>/-/settings/repository#js-protected-branches-settings`.

For this project `main` is protected (no direct push) but does NOT
require approvals, so the 422 is almost always a transient state
that resolves within 30s.

## Related CLAUDE.md rules

- Always pass `--remove-source-branch=false` on MR merges — GitLab's
  default deletes the source branch, which would destroy `dev`.
- Never push directly to `main`. If auto-merge is broken and you
  need to ship urgently, open a hotfix MR instead.
