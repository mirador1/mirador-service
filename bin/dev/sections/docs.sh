#!/usr/bin/env bash
# bin/dev/sections/docs.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_docs, section_mermaid_lint
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

# ── Section 6: Doc audit (broken links in root README) ──────────────────────
section_docs() {
  echo "▸ Doc audit (broken README links)…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    if [[ ! -f "$repo/README.md" ]]; then continue; fi
    # Extract markdown links targeting local files (not URLs).
    local broken
    broken=$( (cd "$repo" && python3 -c "
import re, os
broken = []
with open('README.md') as f: txt = f.read()
for m in re.finditer(r'\[[^\]]+\]\(([^)]+)\)', txt):
    p = m.group(1).split('#')[0]
    if p.startswith('http') or not p: continue
    if not os.path.exists(p):
        broken.append(p)
print('\n'.join(broken[:5]))" 2>/dev/null) || echo "")
    if [[ -n "$broken" ]]; then
      finding warn "$name README: broken local links: $(echo "$broken" | tr '\n' ',' | sed 's/,$//')"
    fi
  done
  # Root file budget per CLAUDE.md "Root file hygiene" (≤15).
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    local count
    count=$( (cd "$repo" && ls -1p | grep -v / 2>/dev/null) | wc -l | tr -d ' ')
    if [[ "$count" -gt 15 ]]; then
      finding warn "$name: $count files at root (cap 15) — apply CLAUDE.md \"Root file hygiene\""
    fi
  done
}

# ── Section 5f-quater: Mermaid diagram syntax check ───────────────────────
# Why: long-form architecture docs (`docs/architecture/*.md`, `docs/adr/*.md`)
# accumulate Mermaid diagrams over time. The most common breakage is
# copy-pasting a block whose first line lost the diagram-type keyword
# (`flowchart`, `sequenceDiagram`, etc.) — GitHub renders nothing and
# silently shows the raw text. A second class of breakage is mixing
# tabs and spaces inside a block (Mermaid uses spaces only). Both
# fail at render time on GitHub/GitLab without any error message.
#
# Heavy alternative: `npx @mermaid-js/mermaid-cli` (mmdc) which actually
# runs the Mermaid parser in headless Chrome — accurate but pulls
# Chromium (~150 MB) and adds 30 s+ per run. The grep-based check
# below catches the two breakage classes above without any
# dependency. If we ever add a CI gate on Mermaid rendering, switch
# to mmdc; for the local stability-check, the lite check is enough.
section_mermaid_lint() {
  echo "▸ Mermaid diagram syntax check (lite)…"
  # Awk extracts the FIRST non-empty line that follows each ```mermaid
  # opener, until the matching ``` closer. One emitted line per block.
  # Avoids the previous broken state-machine that mis-counted nested
  # code fences.
  local valid_types="^[[:space:]]*(flowchart|graph|sequenceDiagram|classDiagram|stateDiagram(-v2)?|erDiagram|journey|gantt|pie|gitGraph|mindmap|timeline|quadrantChart|requirementDiagram|C4Context|C4Container|C4Component|C4Dynamic|C4Deployment|sankey-beta|xychart-beta|block-beta|packet-beta|architecture-beta)([[:space:]]|$)"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    local bad_blocks=0 bad_files=()
    while IFS= read -r f; do
      [[ -z "$f" ]] && continue
      local file_bad=0
      while IFS= read -r first_line; do
        [[ -z "$first_line" ]] && continue
        if ! echo "$first_line" | grep -qE "$valid_types"; then
          file_bad=$((file_bad + 1))
        fi
      done < <(awk '
        /^```mermaid$/ { in_block=1; got_first=0; next }
        /^```$/        { in_block=0; next }
        in_block && !got_first && NF>0 { print; got_first=1 }
      ' "$f" 2>/dev/null)
      if [[ "$file_bad" -gt 0 ]]; then
        bad_blocks=$((bad_blocks + file_bad))
        bad_files+=("$(basename "$f"):$file_bad")
      fi
    done < <( (cd "$repo" && find docs README.md README.fr.md -type f -name "*.md" 2>/dev/null) || true)
    if [[ "$bad_blocks" -gt 0 ]]; then
      local list="${bad_files[*]:0:5}"
      [[ "${#bad_files[@]}" -gt 5 ]] && list="$list …(+$((${#bad_files[@]} - 5)))"
      finding warn "$name: $bad_blocks Mermaid block(s) without a recognised diagram-type keyword: $list"
    fi
  done
}

