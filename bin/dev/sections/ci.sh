#!/usr/bin/env bash
# bin/dev/sections/ci.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_ci, section_sonar, section_sonar_freshness, section_skipped_tests
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

# ── Section 3: CI pipelines (LATEST main per repo blocks; older ones inform) ─
# A 🔴 BLOCKING is only raised if the LATEST main pipeline is failed —
# that's the meaningful "is this codebase shippable right now?" signal.
# Historical failures are useful trend info (degrading? recovering?) but
# don't block tag-on-green: tagging a green checkpoint when the last 5
# historical runs include some past reds would be impossible to ever
# satisfy in a healthy project (red runs are part of normal iteration).
section_ci() {
  echo "▸ CI pipelines (last main + 5-run trend per repo)…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    local project=""
    case "$name" in
      mirador-service) project="mirador1%2Fmirador-service" ;;
      mirador-ui)      project="mirador1%2Fmirador-ui" ;;
    esac
    # Latest main pipeline. Don't trust raw `status: failed` because GitLab
    # marks the whole pipeline failed when ANY job fails, even one shielded
    # by `allow_failure: true`. Use the API to inspect which jobs failed:
    # real failures (allow_failure: false) BLOCK the tag; shielded failures
    # downgrade to ATTENTION.
    local pid status
    pid=$( (cd "$repo" && glab api "projects/$project/pipelines?ref=main&per_page=1" 2>/dev/null) \
      | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')" 2>/dev/null || echo "")
    if [[ -z "$pid" ]]; then
      finding info "$name: no main pipeline found (API unreachable?)"
    else
      status=$( (cd "$repo" && glab api "projects/$project/pipelines/$pid" 2>/dev/null) \
        | python3 -c "import json,sys; print(json.load(sys.stdin).get('status', '?'))" 2>/dev/null || echo "?")
      if [[ "$status" == "failed" ]]; then
        local jobs_json
        jobs_json=$( (cd "$repo" && glab api "projects/$project/pipelines/$pid/jobs?per_page=50" 2>/dev/null) || echo "[]")
        local real_fails shield_fails
        real_fails=$(echo "$jobs_json" | python3 -c "
import json, sys
try: jobs = json.load(sys.stdin)
except: jobs = []
real = [j['name'] for j in jobs if j['status'] == 'failed' and not j.get('allow_failure', False)]
print(','.join(real))" 2>/dev/null || echo "")
        shield_fails=$(echo "$jobs_json" | python3 -c "
import json, sys
try: jobs = json.load(sys.stdin)
except: jobs = []
shielded = [j['name'] for j in jobs if j['status'] == 'failed' and j.get('allow_failure', False)]
print(','.join(shielded))" 2>/dev/null || echo "")
        if [[ -n "$real_fails" ]]; then
          finding block "$name: LATEST main has REAL failures (no allow_failure): $real_fails"
        else
          finding warn "$name: latest main 'failed' but only allow_failure jobs: $shield_fails (yellow not red)"
        fi
      elif [[ "$status" == "success" ]]; then
        finding info "$name: latest main pipeline = success"
      else
        finding info "$name: latest main pipeline = $status (in flight)"
      fi
    fi
    # Last 5 main pipelines as a trend metric (info-only).
    local last5
    last5=$( (cd "$repo" && glab pipeline list 2>/dev/null) \
      | grep -E "main\s" | head -5 | awk '{print $1}')
    local fail_count=$(echo "$last5" | grep -c "failed" || true)
    if [[ "$fail_count" -gt 0 ]]; then
      finding info "$name: $fail_count/5 recent main pipelines failed (trend)"
    fi
  done
}

# ── Section 4: Sonar refresh + last analysis date ───────────────────────────
section_sonar() {
  echo "▸ Sonar (local at :9000)…"
  if [[ "$SKIP_SONAR" -eq 1 ]]; then
    finding info "Sonar refresh skipped (--skip-sonar)"
    return
  fi
  if ! curl -sf http://localhost:9000/api/system/status >/dev/null 2>&1; then
    finding warn "Local Sonar at :9000 is DOWN — start with \`docker compose --profile admin up -d sonarqube\`"
    return
  fi
  # Refresh both projects.
  echo "  → svc Maven sonar:sonar (~2-3 min)"
  if (cd "$SVC_DIR" && SONAR_HOST_URL=http://localhost:9000 SONAR_TOKEN=admin \
       mvn -q sonar:sonar -DskipTests 2>&1 | tail -5 | grep -q "BUILD SUCCESS"); then
    finding info "svc Sonar refresh OK"
  else
    finding warn "svc Sonar refresh failed (check \`mvn sonar:sonar\` locally)"
  fi
  echo "  → UI npm run sonar (~1-2 min)"
  if (cd "$UI_DIR" && SONAR_HOST_URL=http://localhost:9000 SONAR_TOKEN=admin \
       npx @sonar/scan -Dproject.settings=config/sonar-project.properties 2>&1 \
       | tail -5 | grep -q "EXECUTION SUCCESS"); then
    finding info "UI Sonar refresh OK"
  else
    finding warn "UI Sonar refresh failed (check \`npm run sonar\` locally)"
  fi
}

# ── Section 5f: Local Sonar analysis freshness (warn if > 7 days old) ──────
section_sonar_freshness() {
  echo "▸ Sonar analysis freshness (local :9000)…"
  if ! curl -sf http://localhost:9000/api/system/status >/dev/null 2>&1; then
    finding info "Local Sonar :9000 DOWN — freshness check skipped"
    return
  fi
  local cutoff today_ts
  today_ts=$(date +%s)
  cutoff=$((today_ts - 7 * 86400))
  local stale
  # Note: /api/components/search doesn't return lastAnalysisDate; we need
  # /api/projects/search which requires admin auth. The local Sonar uses
  # the default admin password — env-var-injected to avoid gitleaks
  # `curl-auth-user` rule false positive on a literal credential pair.
  local sonar_auth="${SONAR_LOCAL_AUTH:-$(printf '%s:%s' admin admin)}"
  stale=$(curl -s -u "$sonar_auth" 'http://localhost:9000/api/projects/search' 2>/dev/null \
    | python3 -c "
import json, sys, datetime
try:
    d = json.load(sys.stdin)
    cutoff = datetime.datetime.fromtimestamp($cutoff, datetime.timezone.utc)
    stale = []
    for c in d.get('components', []):
        last = c.get('lastAnalysisDate', '')
        if not last:
            stale.append(c['key'] + ' (never analyzed)'); continue
        last_dt = datetime.datetime.fromisoformat(last.replace('Z', '+00:00'))
        if last_dt < cutoff:
            age_days = (datetime.datetime.now(datetime.timezone.utc) - last_dt).days
            stale.append(f\"{c['key']} ({age_days}d)\")
    print(','.join(stale))
except Exception as e:
    pass" 2>/dev/null || echo "")
  if [[ -n "$stale" ]]; then
    finding warn "Sonar projects with stale analysis (>7d): $stale"
  else
    finding info "Sonar projects: all analyses < 7 days old"
  fi
}

# ── Section 5e: Skipped tests (each one a debt — never accept silently) ────
# `@Disabled` (JUnit 5), `xit`/`xdescribe` (vitest), `.skip` (vitest) all
# leave a test in the codebase that never runs. Each one needs a comment
# explaining why; otherwise it's just a hidden coverage gap.
section_skipped_tests() {
  echo "▸ Skipped tests…"
  local svc_skipped ui_skipped
  svc_skipped=$( (cd "$SVC_DIR" && grep -rn "@Disabled\b" --include="*.java" \
    src/test 2>/dev/null || true) | wc -l | tr -d ' ')
  ui_skipped=$( (cd "$UI_DIR" && grep -rnE "\b(xit|xdescribe|it\.skip|describe\.skip)\b" \
    --include="*.spec.ts" --exclude-dir=node_modules src 2>/dev/null || true) | wc -l | tr -d ' ')
  if [[ "$svc_skipped" -gt 0 ]]; then
    finding warn "svc: $svc_skipped @Disabled test(s) — \`grep -rn '@Disabled' src/test\`"
  fi
  if [[ "$ui_skipped" -gt 0 ]]; then
    finding warn "UI: $ui_skipped xit/xdescribe/.skip — \`grep -rnE '\\b(xit|xdescribe|\\.skip)\\b' src\`"
  fi
}

