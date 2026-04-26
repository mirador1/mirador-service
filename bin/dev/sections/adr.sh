#!/usr/bin/env bash
# bin/dev/sections/adr.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_adr_sequence, section_adr_index, section_adr_proposed
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

# ── Section 6b: ADR sequence integrity (no numbering gaps) ─────────────────
# ADR numbers should be consecutive (0001 → 0002 → ...). A missing number
# usually means an ADR was reverted/squashed; either fill the gap or
# document the intentional skip.
section_adr_sequence() {
  echo "▸ ADR sequence integrity…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    if [[ ! -d "$repo/docs/adr" ]]; then continue; fi
    local gap
    gap=$( (cd "$repo" && ls docs/adr/*.md 2>/dev/null) \
      | grep -oE "/[0-9]+-" | grep -oE "[0-9]+" \
      | python3 -c "
import sys
nums = sorted(set(int(n) for n in sys.stdin if n.strip()))
gaps = []
if nums:
    for i in range(min(nums), max(nums) + 1):
        if i not in nums:
            gaps.append(str(i).zfill(4))
print(','.join(gaps))" 2>/dev/null || echo "")
    if [[ -n "$gap" ]]; then
      finding warn "$name: ADR numbering gaps: $gap (fill or document the skip)"
    fi
  done
}

# ── Section 5f-ter: ADR flat-index auto-regen drift (DOC1 Phase 1.5) ──────
# Why: 40+ ADRs in svc; keeping the flat-index table in docs/adr/README.md
# in sync by hand is the kind of chore that rots silently. `bin/dev/regen-adr-index.sh`
# generates the correct table from the ADR files themselves. If the current
# README doesn't match the generated content, someone added/renamed an ADR
# without running the regen script. We flag the drift with the actionable
# fix command so the reviewer knows exactly what to do. Only applies to svc
# (UI has no ADRs today — skips silently when docs/adr/ is absent).
section_adr_index() {
  echo "▸ ADR flat-index auto-regen drift…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    # Prefer the COMMON regenerator (factored 2026-04-26 into mirador-common
    # per ADR-0001 + ADR-0060 — universal layer, separate from backend-only
    # mirador-service-shared). Fall back order :
    #   1. infra/common/bin/dev/regen-adr-index.sh   (current canonical, since 2026-04-26 split)
    #   2. infra/shared/bin/dev/regen-adr-index.sh   (legacy, before common was split out)
    #   3. bin/dev/regen-adr-index.sh                (legacy, before factorisation)
    # Skip silently when none exists (UI repo before it adds infra/common,
    # repos without docs/adr/, etc.).
    local script
    if [[ -x "$repo/infra/common/bin/dev/regen-adr-index.sh" ]]; then
      script="$repo/infra/common/bin/dev/regen-adr-index.sh"
    elif [[ -x "$repo/infra/shared/bin/dev/regen-adr-index.sh" ]]; then
      script="$repo/infra/shared/bin/dev/regen-adr-index.sh"
    elif [[ -x "$repo/bin/dev/regen-adr-index.sh" ]]; then
      script="$repo/bin/dev/regen-adr-index.sh"
    else
      continue
    fi
    if ( cd "$repo" && "$script" --check >/dev/null 2>&1 ); then
      : # OK — table matches
    else
      local rc=$?
      if [[ $rc -eq 2 ]]; then
        finding warn "$name: ADR-INDEX markers missing from docs/adr/README.md — add <!-- ADR-INDEX:START --> / <!-- ADR-INDEX:END -->"
      else
        finding warn "$name: ADR flat index drifted from files — run '$script --in-place' and commit"
      fi
    fi
  done
}

# ── Section 5f-bis: ADR "Proposed" status — flag stuck decisions ──────────
# Why: Michael Nygard's ADR template uses Status: {Proposed, Accepted,
# Rejected, Deprecated, Superseded}. A Proposed ADR is a half-baked
# decision; if it sits in Proposed > 30 days the team has implicitly
# moved on without recording the actual outcome. This section flags
# them as warnings so the next session either accepts/rejects or
# folds the doc back into a real proposal. We use git blame on the
# Status line rather than file mtime so a typo-fix in the body
# doesn't reset the clock.
section_adr_proposed() {
  echo "▸ ADR 'Proposed' status — stuck decisions…"
  local cutoff
  cutoff=$(date -v-30d +%s 2>/dev/null || date -d '30 days ago' +%s)
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    if [[ ! -d "$repo/docs/adr" ]]; then continue; fi
    local stuck=()
    while IFS= read -r f; do
      [[ -z "$f" ]] && continue
      # Match either "- Status: Proposed" (our convention) or
      # "Status: Proposed" plain. Case-insensitive on the value.
      local line_no
      line_no=$( (cd "$repo" && grep -nE "^\s*-?\s*Status:\s*Proposed" "$f" 2>/dev/null | head -1 | cut -d: -f1) || true)
      [[ -z "$line_no" ]] && continue
      local ts
      ts=$( (cd "$repo" && git blame -L "$line_no,$line_no" --porcelain "$f" 2>/dev/null \
        | grep "^author-time " | awk '{print $2}' | head -1) || echo "")
      if [[ -n "$ts" && "$ts" -lt "$cutoff" ]]; then
        stuck+=("$(basename "$f")")
      fi
    done < <( (cd "$repo" && ls docs/adr/*.md 2>/dev/null) || true)
    if [[ "${#stuck[@]}" -gt 0 ]]; then
      local more=""
      [[ "${#stuck[@]}" -gt 5 ]] && more=" …(+$((${#stuck[@]} - 5)))"
      finding warn "$name: ${#stuck[@]} ADR(s) stuck in 'Proposed' >30d: ${stuck[*]:0:5}${more}"
    fi
  done
}

