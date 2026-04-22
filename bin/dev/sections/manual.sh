#!/usr/bin/env bash
# bin/dev/sections/manual.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_manual_jobs
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

# ── Section 8: Optional manual job triggers (--trigger-manual) ──────────────
section_manual_jobs() {
  if [[ "$TRIGGER_MANUAL" -eq 0 ]]; then
    finding info "Manual CI jobs not triggered (use --trigger-manual to opt in)"
    return
  fi
  echo "▸ Triggering manual jobs on latest svc main pipeline…"
  local pid
  pid=$(cd "$SVC_DIR" && glab api 'projects/mirador1%2Fmirador-service/pipelines?ref=main&per_page=1' 2>/dev/null \
    | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
  if [[ -z "$pid" ]]; then
    finding warn "Couldn't get latest svc main pipeline ID — skipping manual triggers"
    return
  fi
  # Safe set: compat tests, mutation, semgrep, build-native, smoke-test.
  # NOT triggered here: deploy:* (require resources), terraform-apply (state risk).
  local safe_jobs="compat-sb3-java17 compat-sb3-java21 compat-sb4-java17 compat-sb4-java21 mutation-test semgrep build-native smoke-test"
  cd "$SVC_DIR" && glab api "projects/mirador1%2Fmirador-service/pipelines/$pid/jobs?per_page=50" 2>/dev/null \
    | python3 -c "
import json, sys
jobs = json.load(sys.stdin)
safe = '$safe_jobs'.split()
for j in jobs:
    if j['status'] == 'manual' and j['name'] in safe:
        print(f\"{j['id']}\t{j['name']}\")" \
    | while IFS=$'\t' read -r jid jname; do
        glab ci trigger "$jid" >/dev/null 2>&1 \
          && finding info "triggered $jname (job $jid)" \
          || finding warn "trigger failed: $jname (job $jid)"
      done
}

