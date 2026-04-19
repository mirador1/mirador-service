#!/usr/bin/env bash
# =============================================================================
# bin/ship.sh — one-command dev → main shipping.
#
# The manual sequence that used to be: commit → push → open MR → arm
# auto-merge → wait for green → pull main → sync dev → push GitHub
# mirror. Seven steps per feature. This script replaces it with one.
#
# Flow:
#   1. Verify the working tree is clean and dev is the current branch.
#   2. Push dev to origin (will be a no-op if already pushed).
#   3. Find or create an MR dev → main (squash-before-merge).
#   4. Arm merge-when-pipeline-succeeds.
#   5. Optional (with --wait): block until the MR merges, then:
#      a. Fetch origin, reset local dev to origin/main.
#      b. Mirror-push to the GitHub mirror (if GitHub remote known).
#
# Usage:
#   bin/ship.sh "fix(scope): subject"       # commits any staged changes
#                                           # with the message, then ships
#   bin/ship.sh                             # commits nothing, just ships
#                                           # the current dev HEAD
#   bin/ship.sh --wait                      # block until merged, then
#                                           # sync dev + push GitHub mirror
#   bin/ship.sh --dry-run                   # print the plan, do nothing
#
# Conventions (enforced by lefthook commit-msg + 72-char limit):
#   * Conventional Commits subject (feat|fix|docs|...)
#   * Max 72 chars in the subject
#   * Body explains the "why" (lefthook doesn't enforce body)
#
# Why this is safe to run repeatedly: each step is idempotent. If the
# MR already exists, the script finds it instead of erroring. If
# auto-merge is already armed, the PUT is a no-op.
#
# Related: docs/ops/ci-philosophy.md (why GitLab is master) and
# docs/ops/github-mirror.md (why GitHub gets a separate push).
# =============================================================================

set -euo pipefail

# ── Config ──────────────────────────────────────────────────────────────────
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# Detect GitLab project path from the `origin` remote (e.g. mirador1/mirador-service).
GITLAB_PROJECT=$(git remote get-url origin 2>/dev/null | sed -E 's|.*gitlab\.com[:/](.+)\.git|\1|')
GITHUB_REPO="${GITHUB_MIRROR:-}"   # Override per repo in an env file if needed
if [[ -z "$GITHUB_REPO" ]]; then
  case "$GITLAB_PROJECT" in
    mirador1/mirador-service) GITHUB_REPO="Beennnn/mirador-service" ;;
    mirador1/mirador-ui)      GITHUB_REPO="Beennnn/mirador-ui" ;;
    *)                        GITHUB_REPO="" ;;
  esac
fi

DEV_BRANCH="${DEV_BRANCH:-dev}"
MAIN_BRANCH="${MAIN_BRANCH:-main}"

# ── Flags ───────────────────────────────────────────────────────────────────
COMMIT_MSG=""
WAIT=0
DRY=0
for arg in "$@"; do
  case "$arg" in
    --wait)     WAIT=1 ;;
    --dry-run)  DRY=1 ;;
    --help|-h)  sed -n '2,25p' "$0"; exit 0 ;;
    *)          COMMIT_MSG="$arg" ;;
  esac
done

# ── Helpers ─────────────────────────────────────────────────────────────────
step() { printf "\033[34m▸\033[0m %s\n" "$1"; }
ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }
bad()  { printf "  \033[31m✗\033[0m %s\n" "$1"; exit 1; }

run() {
  if [[ "$DRY" == "1" ]]; then
    printf "  \033[33m[dry]\033[0m %s\n" "$*"
  else
    eval "$@"
  fi
}

# ── Preconditions ───────────────────────────────────────────────────────────
step "1/5 Preconditions"
branch=$(git branch --show-current)
if [[ "$branch" != "$DEV_BRANCH" ]]; then
  bad "Current branch is '$branch', expected '$DEV_BRANCH'."
fi
ok "on $DEV_BRANCH"

if [[ -n "$COMMIT_MSG" ]]; then
  if ! git diff --cached --quiet; then
    step "1b/5 Creating commit"
    run git commit -m "\"$COMMIT_MSG\""
    ok "committed"
  else
    ok "no staged changes — skipping commit, shipping current HEAD"
  fi
elif [[ -n "$(git status --porcelain)" ]]; then
  bad "Working tree dirty and no commit message given. Stage + re-run with bin/ship.sh \"message\" or commit manually first."
fi

# ── Push ────────────────────────────────────────────────────────────────────
step "2/5 Push $DEV_BRANCH to origin"
# --force-with-lease is safe in the solo-dev + squash-merge workflow:
# after a squash merge the local dev is ahead of origin/dev with a
# cherry-picked commit whose content is already on main. Force-with-
# lease aborts if someone else pushed, which catches the rare
# concurrent-work case without burning our own history.
run git push origin "$DEV_BRANCH" --force-with-lease
ok "pushed"

# ── MR ──────────────────────────────────────────────────────────────────────
step "3/5 Find or create MR"
project_url=$(printf '%s' "$GITLAB_PROJECT" | sed 's|/|%2F|')
existing=$(glab api "projects/$project_url/merge_requests?state=opened&source_branch=$DEV_BRANCH" \
           --paginate 2>/dev/null | python3 -c "
import sys, json
data = json.load(sys.stdin)
for m in data:
    if m.get('source_branch') == '$DEV_BRANCH' and m.get('target_branch') == '$MAIN_BRANCH' and m.get('state') == 'opened':
        print(m.get('iid'))
        break
" 2>/dev/null)

if [[ -n "$existing" ]]; then
  MR_IID="$existing"
  ok "reusing MR !$MR_IID"
else
  # Use the latest commit's subject as the MR title.
  title=$(git log -1 --format='%s' "$DEV_BRANCH")
  body=$(git log "$MAIN_BRANCH..$DEV_BRANCH" --format='* %s' | head -20)
  if [[ "$DRY" == "1" ]]; then
    printf "  \033[33m[dry]\033[0m glab mr create --title '%s' --squash-before-merge\n" "$title"
    MR_IID="<dry>"
  else
    # glab writes the created URL to stdout. Capture everything to a
    # tempfile so ANSI codes + stderr interleaving don't break the
    # grep, then extract the IID from the URL. `|| true` so a transient
    # error doesn't trip `set -e`; we verify via $MR_IID right below.
    glab mr create \
      --title "$title" \
      --description "$body" \
      --target-branch "$MAIN_BRANCH" \
      --source-branch "$DEV_BRANCH" \
      --squash-before-merge \
      >/tmp/ship-mr-create.log 2>&1 || true
    MR_IID=$(grep -oE 'merge_requests/[0-9]+' /tmp/ship-mr-create.log | head -1 | grep -oE '[0-9]+$')
    if [[ -z "$MR_IID" ]]; then
      bad "couldn't parse MR IID from glab output. Log: $(cat /tmp/ship-mr-create.log)"
    fi
    ok "opened MR !$MR_IID"
  fi
fi

# ── Arm auto-merge ──────────────────────────────────────────────────────────
step "4/5 Arm auto-merge on MR !$MR_IID (squash=true)"
if [[ "$DRY" == "1" ]]; then
  printf "  \033[33m[dry]\033[0m glab api PUT merge_requests/$MR_IID/merge merge_when_pipeline_succeeds=true\n"
else
  # Retry a few times — new MR pipelines need a few seconds to register
  # before the `merge` endpoint accepts MWPS. We don't grep the arm
  # response (GitLab's MR JSON is >2 KB and the field lives deep);
  # instead we re-fetch and verify.
  for attempt in 1 2 3 4 5; do
    glab api --method PUT "projects/$project_url/merge_requests/$MR_IID/merge" \
      -f merge_when_pipeline_succeeds=true \
      -f should_remove_source_branch=false \
      -f squash=true >/dev/null 2>&1 || true
    sleep 2
    mwps=$(glab api "projects/$project_url/merge_requests/$MR_IID" 2>/dev/null \
           | python3 -c "import sys,json; print(json.load(sys.stdin).get('merge_when_pipeline_succeeds'))")
    if [[ "$mwps" == "True" ]]; then
      ok "MWPS armed"
      break
    fi
    sleep 3
    [[ "$attempt" == "5" ]] && bad "couldn't arm MWPS after 5 tries. Current MWPS=$mwps"
  done
fi

# ── Wait + sync + mirror ────────────────────────────────────────────────────
if [[ "$WAIT" == "0" ]]; then
  echo
  ok "Shipped. MR !$MR_IID will auto-merge when the pipeline goes green."
  ok "https://gitlab.com/$GITLAB_PROJECT/-/merge_requests/$MR_IID"
  exit 0
fi

step "5/5 Waiting for MR !$MR_IID to merge (polling every 30s)…"
while true; do
  state=$(glab api "projects/$project_url/merge_requests/$MR_IID" 2>/dev/null \
          | python3 -c "import sys,json; print(json.load(sys.stdin).get('state',''))")
  case "$state" in
    merged)  ok "merged ✅"; break ;;
    closed)  bad "MR was closed without merging." ;;
    opened)  printf "  …still open (pipeline running)\r"; sleep 30 ;;
    *)       bad "unknown MR state: $state" ;;
  esac
done

step "5b/5 Sync local $DEV_BRANCH with origin/$MAIN_BRANCH"
# After a squash merge, dev is ahead of origin/main with a cherry-picked
# commit that's already on main (different SHA, same content). Cleanest
# sync: fetch, hard-reset dev to origin/main.
run git fetch --all --quiet
run git reset --hard "origin/$MAIN_BRANCH"
# Non-destructive: the commits on the old dev are already on main via
# the squash, plus preserved in `git reflog` for 90 days.
run git push origin "$DEV_BRANCH" --force-with-lease
ok "dev in sync with main"

if [[ -n "$GITHUB_REPO" ]]; then
  step "5c/5 Mirror-push to github.com/$GITHUB_REPO"
  tmpdir=$(mktemp -d)
  if [[ "$DRY" == "1" ]]; then
    printf "  \033[33m[dry]\033[0m git clone --mirror + git push --mirror to github.com/%s\n" "$GITHUB_REPO"
  else
    (cd "$tmpdir" && \
     git clone --mirror "https://gitlab.com/$GITLAB_PROJECT.git" mirror.git >/dev/null 2>&1 && \
     cd mirror.git && \
     git push --mirror "https://github.com/$GITHUB_REPO.git" >/dev/null 2>&1) \
      && ok "pushed to GitHub" \
      || printf "  \033[33m!\033[0m GitHub push failed (probably auth — set up the deploy key or PAT).\n"
    rm -rf "$tmpdir"
  fi
fi

echo
ok "Done. main is up to date, $DEV_BRANCH is in sync, GitHub mirror refreshed."
