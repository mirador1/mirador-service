#!/usr/bin/env bash
# =============================================================================
# bin/admin/gitlab-housekeeping.sh — desactivate sidebar features Mirador
# doesn't use (UI clutter cleanup).
#
# What this disables (idempotent — re-runnable safely):
#
# Côté GitLab (via glab API, free tier compatible):
#   - Wiki                 — redondant avec docs/ in-repo (50+ ADRs +
#                            audits + runbooks under git review)
#   - Secure UI sidebar    — teasers Ultimate ($99/mo); CI scans run
#                            (free) but l'UI promo n'apporte rien
#
# Côté GitHub (via gh CLI):
#   - Issues               — confusion ("où reporter": GitLab a les MRs
#                            + CI, GitHub est mirror code-only)
#   - Wiki                 — same redundancy reason as GitLab
#
# What this does NOT touch:
#   - GitLab AI Duo        (payant, jamais activé sans souscription)
#   - GitLab Releases      (vide aujourd'hui, géré par bin/ship/gitlab-release.sh)
#   - GitLab Feature Flags (pas de toggle standalone, vit sous operations)
#   - Terraform state /-/terraform (GCS backend déjà optimal — pas migration)
#   - CI security jobs (sast/trivy/sbom/grype/dockle/cosign) — gardés,
#     ils tournent indépendamment du Secure UI
#
# Usage:
#   bin/admin/gitlab-housekeeping.sh           # apply (after --dry-run review)
#   bin/admin/gitlab-housekeeping.sh --dry-run # preview, no API calls
#   bin/admin/gitlab-housekeeping.sh --status  # show current state only
#
# Repos affectés (les 2):
#   mirador1/mirador-service  (svc, GitLab + GitHub mirror)
#   mirador1/mirador-ui       (UI, GitLab + GitHub mirror)
#
# Related:
#   - Cleanup theme cohérent — voir CLAUDE.md notes from 2026-04-22
#   - Toutes ces désactivations sont REVERSIBLES via le même chemin API
#     (passer "enabled" au lieu de "disabled") OU via UI Settings.
# =============================================================================
set -euo pipefail

DRY_RUN=false
STATUS_ONLY=false
case "${1:-}" in
  --dry-run)   DRY_RUN=true ;;
  --status)    STATUS_ONLY=true ;;
  --help|-h)   sed -n '1,40p' "$0" | head -40; exit 0 ;;
  "")          ;;
  *)           echo "Unknown option: $1"; exit 1 ;;
esac

GITLAB_REPOS=("mirador1/mirador-service" "mirador1/mirador-ui")
GITHUB_REPOS=("mirador1/mirador-service" "mirador1/mirador-ui")

# ─────────────────────────────────────────────────────────────────────────────
# 1. STATUS — show current state (always run)
# ─────────────────────────────────────────────────────────────────────────────
echo "▶️  Current state:"
for repo in "${GITLAB_REPOS[@]}"; do
  encoded="${repo//\//%2F}"
  printf "\n  GitLab %s:\n" "$repo"
  glab api "projects/$encoded" 2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
for k in ['wiki_access_level', 'security_and_compliance_access_level',
          'releases_access_level', 'pages_access_level', 'issues_access_level']:
    v = d.get(k, '?')
    print(f'    {k:42s} = {v}')
"
done
echo ""
for repo in "${GITHUB_REPOS[@]}"; do
  printf "  GitHub %s:\n" "$repo"
  gh repo view "$repo" --json hasIssuesEnabled,hasWikiEnabled,hasProjectsEnabled \
    2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'    issues  = {d.get(\"hasIssuesEnabled\", \"?\")}')
print(f'    wiki    = {d.get(\"hasWikiEnabled\", \"?\")}')
print(f'    projects = {d.get(\"hasProjectsEnabled\", \"?\")}')
" 2>/dev/null || echo "    (gh not authenticated or repo not accessible)"
done

if $STATUS_ONLY; then
  exit 0
fi

# ─────────────────────────────────────────────────────────────────────────────
# 2. APPLY (or preview)
# ─────────────────────────────────────────────────────────────────────────────
echo ""
if $DRY_RUN; then
  echo "▶️  DRY-RUN — would execute:"
else
  echo "▶️  Applying changes:"
fi
echo ""

run() {
  if $DRY_RUN; then
    echo "    $*"
  else
    echo "    > $*"
    eval "$@"
  fi
}

for repo in "${GITLAB_REPOS[@]}"; do
  encoded="${repo//\//%2F}"
  echo "  GitLab $repo — disable wiki + secure UI:"
  run glab api -X PUT "projects/$encoded" --field "wiki_access_level=disabled" '>' /dev/null
  run glab api -X PUT "projects/$encoded" --field "security_and_compliance_access_level=disabled" '>' /dev/null
  echo ""
done

for repo in "${GITHUB_REPOS[@]}"; do
  echo "  GitHub $repo — disable issues + wiki:"
  run gh repo edit "$repo" --enable-issues=false --enable-wiki=false
  echo ""
done

if $DRY_RUN; then
  echo "✅ DRY-RUN complete. Re-run without --dry-run to apply."
else
  echo "✅ Cleanup applied. Re-run with --status to verify."
fi
