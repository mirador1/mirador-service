#!/usr/bin/env bash
# bin/dev/sections/security.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_cve, section_trivy_delta
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

# ── Section 5c: npm audit + Maven dep-check (UI only, fast path) ────────────
# `npm audit --json` is local and fast (~2s). High/critical vulns surface
# as 🟡 ATTENTION; the threshold matches CLAUDE.md "warnings are reds too".
# We deliberately don't run `mvn dependency-check:check` here — it takes
# ~5 min on a cold NVD cache. The CI's `dependency-check` job covers it.
section_cve() {
  echo "▸ CVE audit (npm audit on UI)…"
  local crit high audit_json
  # Single npm audit call, parse twice. Falls back to 0/0 on any error.
  audit_json=$( (cd "$UI_DIR" && npm audit --json 2>/dev/null) || echo '{}')
  crit=$(echo "$audit_json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get('metadata', {}).get('vulnerabilities', {}).get('critical', 0))
except: print(0)" 2>/dev/null | tail -1)
  high=$(echo "$audit_json" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get('metadata', {}).get('vulnerabilities', {}).get('high', 0))
except: print(0)" 2>/dev/null | tail -1)
  # Defensive default if parsing produced empty/non-numeric.
  [[ -z "$crit" || ! "$crit" =~ ^[0-9]+$ ]] && crit=0
  [[ -z "$high" || ! "$high" =~ ^[0-9]+$ ]] && high=0
  if [[ "$crit" -gt 0 ]]; then
    finding block "UI: $crit CRITICAL CVE(s) — \`npm audit\` for details"
  fi
  if [[ "$high" -gt 0 ]]; then
    finding warn "UI: $high HIGH CVE(s) — \`npm audit\` for details"
  fi
  if [[ "$crit" -eq 0 && "$high" -eq 0 ]]; then
    finding info "UI: 0 critical, 0 high CVEs"
  fi
}

# ── Section 5c2: Trivy filesystem CVE delta (new CVEs only) ────────────────
# Running `trivy fs` every stability check flags the same ~20 HIGH/CRITICAL
# transitive CVEs every time — the recurring noise buries real deltas. This
# section stores the sorted list of CVE IDs from the previous scan and only
# reports entries that are NEW since then (per-CVE-ID delta). That way the
# audit report surfaces what actually changed, not an unchanging backlog.
#
# Runtime: ~10s cold, <1s warm (Trivy caches the vuln DB at ~/.cache/trivy).
# Skipped if `trivy` binary is not installed — nothing to do, and CI's own
# `trivy-scan` job covers image-layer coverage.
section_trivy_delta() {
  echo "▸ Trivy CVE delta (new HIGH/CRITICAL since last run)…"
  if ! command -v trivy >/dev/null 2>&1; then
    finding info "Trivy: binary not installed — skip (brew install trivy)"
    return
  fi
  local out=/tmp/trivy.json
  # `fs` scans the current directory tree. `--scanners vuln` limits to CVE
  # detection (skip misconfigs/secrets — covered by other jobs). `--quiet`
  # hides the progress bar; `--format json` gives us the structured result.
  if ! (cd "$SVC_DIR" && trivy fs --scanners vuln \
        --severity HIGH,CRITICAL --format json --quiet \
        --output "$out" . 2>/dev/null); then
    finding warn "Trivy: scan failed — run \`trivy fs --scanners vuln .\` manually"
    return
  fi
  # Extract a sorted list of "SEVERITY CVE-ID pkg@version" tuples from the
  # Results[].Vulnerabilities[] shape. De-dupe by (CVE-ID, package) —
  # Trivy lists the same CVE once per file that transitively pulls it in.
  local parsed
  parsed=$(python3 -c "
import json
try:
    d = json.load(open('$out'))
    seen = set()
    for r in d.get('Results', []) or []:
        for v in r.get('Vulnerabilities', []) or []:
            key = f\"{v.get('Severity','?')} {v.get('VulnerabilityID','?')} {v.get('PkgName','?')}@{v.get('InstalledVersion','?')}\"
            seen.add(key)
    for k in sorted(seen):
        print(k)
except Exception as e:
    pass" 2>/dev/null || true)
  local baseline="$REPORT_DIR/.trivy-last.json"
  # Build the current set of CVE IDs (for storage + delta comparison).
  local current_ids
  current_ids=$(echo "$parsed" | awk '{print $2}' | sort -u | grep -v '^$' || true)
  if [[ -z "$current_ids" ]]; then
    finding info "Trivy: 0 HIGH/CRITICAL CVEs in svc filesystem"
    # Still persist an empty list so next run has a baseline.
    echo "[]" > "$baseline"
    return
  fi
  # Compare against previous baseline.
  if [[ -f "$baseline" ]]; then
    local prev_ids new_ids
    prev_ids=$(python3 -c "
import json
try: print('\n'.join(json.load(open('$baseline'))))
except: pass" 2>/dev/null || true)
    # `comm -23` emits lines only in the first set. Requires sorted input.
    new_ids=$(comm -23 \
      <(echo "$current_ids" | sort) \
      <(echo "$prev_ids" | sort) 2>/dev/null || true)
    if [[ -z "$new_ids" ]]; then
      local cur_count
      cur_count=$(echo "$current_ids" | wc -l | tr -d ' ')
      finding info "Trivy: 0 new CVEs since last run ($cur_count still present)"
    else
      # Emit one line per new CVE with severity + package (from $parsed).
      local new_crit=0 new_high=0
      while IFS= read -r cve; do
        [[ -z "$cve" ]] && continue
        local detail
        detail=$(echo "$parsed" | grep -F " $cve " | head -1)
        [[ -z "$detail" ]] && detail="$cve"
        finding info "Trivy NEW: $detail"
        if echo "$detail" | grep -q "^CRITICAL "; then
          new_crit=$((new_crit + 1))
        elif echo "$detail" | grep -q "^HIGH "; then
          new_high=$((new_high + 1))
        fi
      done <<< "$new_ids"
      if [[ "$new_crit" -gt 0 ]]; then
        finding block "Trivy: $new_crit NEW CRITICAL CVE(s) since last run"
      fi
      if [[ "$new_high" -gt 0 ]]; then
        finding warn "Trivy: $new_high NEW HIGH CVE(s) since last run"
      fi
    fi
  else
    local cur_count
    cur_count=$(echo "$current_ids" | wc -l | tr -d ' ')
    finding info "Trivy: baseline recorded ($cur_count HIGH/CRITICAL CVE(s) — deltas from next run)"
  fi
  # Persist current CVE-ID list as a JSON array for the next run.
  python3 -c "
import json
ids = '''$current_ids'''.strip().split('\n')
ids = [i for i in ids if i]
json.dump(ids, open('$baseline', 'w'))" 2>/dev/null || true
}

