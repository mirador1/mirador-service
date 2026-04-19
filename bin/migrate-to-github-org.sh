#!/usr/bin/env bash
# =============================================================================
# bin/migrate-to-github-org.sh — move GitHub mirrors from `Beennnn/*`
# to an organization (default `mirador1`) and rewrite every URL in
# both repos so the mirror keeps working.
#
# Why: the GitLab namespace is `gitlab.com/mirador1/*`. Mirroring to
# `github.com/Beennnn/*` is a personal-account artefact. Using
# `github.com/mirador1/*` on both platforms makes links symmetric,
# scales to multiple repos without polluting a personal account, and
# is what recruiters would expect when they see a project-scoped
# namespace.
#
# Prereqs (not automated — GitHub gates org creation):
#   1. Create the GitHub org manually at
#      https://github.com/organizations/new (free plan).
#   2. Make sure the local `gh` CLI is authenticated as a member of
#      the org.
#
# What this script does:
#   1. Transfer Beennnn/mirador-service → <ORG>/mirador-service
#   2. Transfer Beennnn/mirador-ui      → <ORG>/mirador-ui
#   3. Wait for GitHub to finalise the redirect.
#   4. Rewrite every hard-coded Beennnn/ URL in both repos:
#        - README.md (badge URLs)
#        - .gitlab-ci.yml (GITHUB_REPO env var)
#        - docs/ops/*.md (examples, cross-references)
#        - bin/ship.sh (GITHUB_REPO default map)
#   5. Commit the rewrites on the current dev branch of each repo.
#
# What this does NOT do:
#   - Re-push deploy keys: GitHub migrates them automatically on
#     transfer (verified in practice 2026-04; documented support
#     article: https://docs.github.com/en/migrations).
#   - Re-configure the CI mirror job: it reads GITHUB_REPO from the
#     job definition, which step 4 has rewritten.
#   - Migrate the GITHUB_MIRROR_SSH_KEY GitLab variable: the key is
#     valid against the same keypair regardless of which GitHub org
#     hosts the repo.
#
# Usage:
#   bin/migrate-to-github-org.sh                    # default org = mirador1
#   bin/migrate-to-github-org.sh mynewname          # override org name
#   bin/migrate-to-github-org.sh --rewrite-only     # skip transfer, just update URLs
# =============================================================================

set -euo pipefail

NEW_ORG="${1:-mirador1}"
OLD_OWNER="${OLD_OWNER:-Beennnn}"
REWRITE_ONLY=0
for arg in "$@"; do
  [[ "$arg" == "--rewrite-only" ]] && REWRITE_ONLY=1
done

SERVICE_DIR="$(cd "$(dirname "$0")/.." && pwd)"
UI_DIR="${UI_DIR:-$SERVICE_DIR/../../js/mirador-ui}"

if [[ ! -d "$UI_DIR" ]]; then
  echo "❌  UI dir not found at $UI_DIR — set UI_DIR=/path/to/mirador-ui"
  exit 1
fi

step() { printf "\033[34m▸\033[0m %s\n" "$1"; }
ok()   { printf "  \033[32m✓\033[0m %s\n" "$1"; }

# ── 1. Transfer (skipped with --rewrite-only) ──────────────────────────────
if [[ "$REWRITE_ONLY" == "0" ]]; then
  step "1/3 Transfer GitHub repos to $NEW_ORG"
  for repo in mirador-service mirador-ui; do
    existing_target=$(gh api "repos/$NEW_ORG/$repo" --jq '.full_name' 2>/dev/null || echo "")
    if [[ "$existing_target" == "$NEW_ORG/$repo" ]]; then
      ok "$NEW_ORG/$repo already exists — skipping transfer"
      continue
    fi
    echo "  transferring $OLD_OWNER/$repo → $NEW_ORG/$repo"
    gh api --method POST "repos/$OLD_OWNER/$repo/transfer" -f new_owner="$NEW_ORG" >/dev/null
    ok "transfer requested"
  done

  step "2/3 Wait for GitHub to finalise"
  # Transfers are synchronous in the API but the redirect takes a few
  # seconds to propagate. 10 s is safe; 30 s if flaky.
  for i in 1 2 3 4 5 6; do
    if gh api "repos/$NEW_ORG/mirador-service" --jq '.full_name' >/dev/null 2>&1; then
      ok "redirect live after ${i}×5s"
      break
    fi
    sleep 5
  done
fi

# ── 3. Rewrite every hard-coded URL ────────────────────────────────────────
step "3/3 Rewrite URLs in both repos"

rewrite_in() {
  local dir="$1"
  cd "$dir"
  # Use find + perl for portable in-place edit (macOS `sed -i ''` vs GNU).
  # Target: any occurrence of github.com/Beennnn/ or the env-var form.
  find . \
    -type f \
    \( -name "*.md" -o -name "*.yml" -o -name "*.yaml" -o -name "*.sh" \
       -o -name "*.svg" -o -name "README*" \) \
    ! -path "./node_modules/*" \
    ! -path "./.git/*" \
    ! -path "./target/*" \
    ! -path "./dist/*" \
    -print0 \
    | xargs -0 perl -i -pe "s{github\.com/${OLD_OWNER}/mirador-(service|ui)}{github.com/${NEW_ORG}/mirador-\$1}g; s{\"${OLD_OWNER}/mirador-(service|ui)\"}{\"${NEW_ORG}/mirador-\$1\"}g;"
  ok "rewritten $(basename "$dir")"
}

rewrite_in "$SERVICE_DIR"
rewrite_in "$UI_DIR"

echo
ok "Migration complete. Commit the rewrites with:"
echo "  cd $SERVICE_DIR && bin/ship.sh \"chore: GitHub org $OLD_OWNER → $NEW_ORG\""
echo "  cd $UI_DIR      && bin/ship.sh \"chore: GitHub org $OLD_OWNER → $NEW_ORG\""
