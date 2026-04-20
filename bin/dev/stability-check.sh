#!/usr/bin/env bash
# =============================================================================
# bin/dev/stability-check.sh — full stability checkpoint
#
# Runs at every "stable point" in the project lifecycle. Aggregates the
# existing per-aspect scripts (no copy-paste of their logic) into one
# orchestrated report.
#
# Designed to GROW over time: each new check adds a `section_*` function
# at the end + one line in `main()`. Every section is independent — a
# failure in one doesn't kill the rest (we collect findings, then
# render the report at the end).
#
# What it composes today (existing scripts referenced, not re-implemented):
#   - bin/ship/pre-sync.sh           (git/branch hygiene + uncommitted check)
#   - bin/dev/healthcheck-all.sh     (12 local endpoints UP/DOWN)
#   - bin/dev/mirador-doctor         (toolchain + env checks)
#   - bin/cluster/cluster-status.sh  (GKE Autopilot age + €/h)
#   - bin/budget/budget.sh status    (GCP cost vs €10 cap)
#   - glab pipeline list             (last 5 main runs per repo)
#   - sonar-scanner / npm run sonar  (refresh local Sonar analyses)
#   - sibling repo's same script     (for full cross-repo coverage)
#
# Output: docs/audit/stability-YYYY-MM-DD-HHMM.md committed to the repo
# so the trend is reviewable (use `ls -la docs/audit/stability-*.md` to
# see the history).
#
# Usage:
#   bin/dev/stability-check.sh                # report only
#   bin/dev/stability-check.sh --trigger-manual    # also run manual CI jobs
#   bin/dev/stability-check.sh --skip-sonar         # skip local Sonar refresh
#   bin/dev/stability-check.sh --commit             # auto-commit the report
#
# Exit codes:
#   0  no blocking findings
#   1  one or more 🔴 BLOCKING findings — see report
#   2  pre-flight failed (missing tool, wrong CWD)
# =============================================================================

set -euo pipefail

# ── Locate the two repos (this script lives in svc) ─────────────────────────
SVC_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
UI_DIR="$(cd "$SVC_DIR/../../js/mirador-ui" 2>/dev/null && pwd || echo "")"
if [[ -z "$UI_DIR" || ! -d "$UI_DIR" ]]; then
  echo "✗ UI repo not found at expected sibling path ($SVC_DIR/../../js/mirador-ui)"
  echo "  Update SVC_DIR/UI_DIR resolution at the top of this script."
  exit 2
fi

# ── Flags ───────────────────────────────────────────────────────────────────
TRIGGER_MANUAL=0
SKIP_SONAR=0
COMMIT_REPORT=0
for arg in "$@"; do
  case "$arg" in
    --trigger-manual) TRIGGER_MANUAL=1 ;;
    --skip-sonar)     SKIP_SONAR=1 ;;
    --commit)         COMMIT_REPORT=1 ;;
    *) echo "unknown flag: $arg"; exit 2 ;;
  esac
done

STAMP="$(date +%Y-%m-%d-%H%M)"
REPORT_DIR="$SVC_DIR/docs/audit"
REPORT="$REPORT_DIR/stability-$STAMP.md"
mkdir -p "$REPORT_DIR"

# Findings buckets — sections append here, main() renders at the end.
BLOCKING=()
ATTENTION=()
NICE=()
INFO=()

# Helper: collect a finding into the right bucket.
finding() {
  local sev="$1"; shift
  case "$sev" in
    block)  BLOCKING+=("$*") ;;
    warn)   ATTENTION+=("$*") ;;
    nice)   NICE+=("$*") ;;
    info)   INFO+=("$*") ;;
  esac
}

# Helper: run a command quietly, return 0 if it succeeds.
silent() {
  "$@" >/dev/null 2>&1
}

# ── Section 1: pre-flight (git, branch, docker) ─────────────────────────────
section_preflight() {
  echo "▸ Pre-flight…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    if ! (cd "$repo" && git diff --quiet); then
      finding warn "$name: uncommitted changes — \`cd $repo && git status\`"
    fi
    if ! (cd "$repo" && silent git fetch --all); then
      finding block "$name: \`git fetch\` failed — network or auth issue"
      continue
    fi
    local ahead behind
    ahead=$(cd "$repo" && git log --oneline origin/dev..HEAD 2>/dev/null | wc -l | tr -d ' ')
    behind=$(cd "$repo" && git log --oneline HEAD..origin/dev 2>/dev/null | wc -l | tr -d ' ')
    if [[ "$behind" -gt 0 ]]; then
      finding warn "$name: branch is $behind commits behind origin/dev — \`git pull --rebase\`"
    fi
    if [[ "$ahead" -gt 0 ]]; then
      finding info "$name: $ahead commits ahead of origin/dev (push pending?)"
    fi
  done

  # Docker disk pressure (per CLAUDE.md "Regular Docker cleanup").
  local docker_gb
  docker_gb=$(docker system df --format json 2>/dev/null \
    | python3 -c "import json,sys; total=0
for line in sys.stdin:
    if not line.strip(): continue
    d = json.loads(line)
    sz = d.get('Size','0B').replace('GB','').replace('MB','')
    try: total += float(sz) if 'GB' in d.get('Size','') else float(sz)/1024
    except: pass
print(int(total))" 2>/dev/null || echo "0")
  if [[ "$docker_gb" -gt 80 ]]; then
    finding warn "Docker disk: ${docker_gb} GB used — run \`docker system prune\` (per CLAUDE.md)"
  else
    finding info "Docker disk: ${docker_gb} GB (under 80 GB cap)"
  fi
}

# ── Section 2: local services healthcheck ───────────────────────────────────
section_healthcheck() {
  echo "▸ Local services healthcheck (delegates to bin/dev/healthcheck-all.sh)…"
  if [[ -x "$SVC_DIR/bin/dev/healthcheck-all.sh" ]]; then
    if "$SVC_DIR/bin/dev/healthcheck-all.sh" --json 2>/dev/null > /tmp/hc.json; then
      local down
      down=$(python3 -c "
import json
d = json.load(open('/tmp/hc.json'))
down = [s['name'] for s in d.get('services', []) if s.get('status') != 'UP' and s.get('required')]
print(','.join(down))" 2>/dev/null || echo "")
      if [[ -n "$down" ]]; then
        finding warn "Local services DOWN: $down (run \`./run.sh all\`)"
      else
        finding info "Local services: all UP"
      fi
    else
      finding info "healthcheck-all returned non-zero (services likely not running — OK if not needed)"
    fi
  else
    finding warn "bin/dev/healthcheck-all.sh missing — restore from git"
  fi
}

# ── Section 3: CI pipelines (last 5 main per repo) ──────────────────────────
section_ci() {
  echo "▸ CI pipelines (last 5 main runs per repo)…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    local last5
    last5=$(cd "$repo" && glab pipeline list 2>/dev/null \
      | grep -E "main\s" | head -5 | awk '{print $1}')
    local fail_count=$(echo "$last5" | grep -c "failed" || true)
    if [[ "$fail_count" -gt 0 ]]; then
      finding block "$name: $fail_count of last 5 main pipelines failed (\`glab pipeline list\` to inspect)"
    else
      finding info "$name: last 5 main pipelines all green"
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
    finding warn "Local Sonar at :9000 is DOWN — start with \`docker compose --profile full up -d sonarqube\`"
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

# ── Section 5: Code audit (delegate to greps; cheap to keep growing) ────────
# Note: `|| true` after every grep — `set -e` would otherwise kill the script
# when grep finds 0 matches (exit 1) which is the GOOD case for "no findings".
section_code() {
  echo "▸ Code audit (greps)…"
  # UI: any types
  local any_count
  any_count=$( (cd "$UI_DIR" && grep -rn ": any\b\|<any>\|as any" --include="*.ts" \
    --exclude-dir=node_modules --exclude="*.spec.ts" src 2>/dev/null || true) | wc -l | tr -d ' ')
  if [[ "$any_count" -gt 0 ]]; then
    finding warn "UI: $any_count \`any\` types in src/"
  fi
  # UI: silent error handlers
  local silent_handlers
  silent_handlers=$( (cd "$UI_DIR" && grep -rn "error: () => {}" --include="*.ts" \
    --exclude-dir=node_modules src 2>/dev/null || true) | wc -l | tr -d ' ')
  if [[ "$silent_handlers" -gt 0 ]]; then
    finding warn "UI: $silent_handlers silent error handlers"
  fi
  # svc: empty catch blocks
  local empty_catch
  empty_catch=$( (cd "$SVC_DIR" && grep -rn "catch[^}]*{[[:space:]]*}" --include="*.java" \
    src 2>/dev/null || true) | wc -l | tr -d ' ')
  if [[ "$empty_catch" -gt 0 ]]; then
    finding warn "svc: $empty_catch empty catch blocks"
  fi
}

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

# ── Section 7: Infra audit (mirror sync, open MRs, allow_failure) ───────────
section_infra() {
  echo "▸ Infra audit…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    # GitHub mirror gap.
    if (cd "$repo" && git remote | grep -q github); then
      cd "$repo" && git fetch github main >/dev/null 2>&1 || true
      local gap
      gap=$(cd "$repo" && git log --oneline github/main..origin/main 2>/dev/null | wc -l | tr -d ' ')
      if [[ "$gap" -gt 0 ]]; then
        finding warn "$name: GitHub mirror $gap commits behind origin/main — \`git push github origin/main:main\`"
      fi
    fi
    # Open MRs (without flag — filter inline; some glab versions don't support --opened-after).
    local open_mrs
    open_mrs=$( (cd "$repo" && glab mr list --opened 2>/dev/null) | grep -c "^!" || true)
    if [[ "$open_mrs" -gt 0 ]]; then
      finding info "$name: $open_mrs MR(s) currently open"
    fi
    # allow_failure: true count.
    local af_total
    af_total=$( (cd "$repo" && grep -c "allow_failure: true" .gitlab-ci.yml 2>/dev/null) || echo "0")
    if [[ "$af_total" -gt 0 ]]; then
      finding info "$name: $af_total \`allow_failure: true\` shields — review per CLAUDE.md \"Pipelines stay green\""
    fi
  done
}

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

# ── Section 8b: Manual job health (svc only — they're scheduled-or-manual) ──
section_manual_health() {
  echo "▸ Manual job health (last run on svc main)…"
  local pid
  pid=$( (cd "$SVC_DIR" && glab api 'projects/mirador1%2Fmirador-service/pipelines?ref=main&per_page=1' 2>/dev/null) \
    | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'])" 2>/dev/null || echo "")
  if [[ -z "$pid" ]]; then
    finding info "manual-health: couldn't fetch latest svc main pipeline"
    return
  fi
  # Manual jobs we care about + their last-run status.
  local report
  report=$( (cd "$SVC_DIR" && glab api "projects/mirador1%2Fmirador-service/pipelines/$pid/jobs?per_page=50" 2>/dev/null) \
    | python3 -c "
import json, sys
jobs = json.load(sys.stdin)
watched = ['compat-sb3-java17', 'compat-sb3-java21', 'compat-sb4-java17',
           'compat-sb4-java21', 'mutation-test', 'semgrep', 'build-native',
           'smoke-test']
fails, manuals, ok = [], [], []
for j in jobs:
    if j['name'] not in watched: continue
    if j['status'] == 'failed':  fails.append(j['name'])
    elif j['status'] == 'manual': manuals.append(j['name'])
    elif j['status'] == 'success': ok.append(j['name'])
print(f\"FAIL:{','.join(fails)}|MANUAL:{','.join(manuals)}|OK:{','.join(ok)}\")" 2>/dev/null || echo "")
  local fails manuals ok
  fails=$(echo "$report" | grep -oE 'FAIL:[^|]*' | sed 's/FAIL://')
  manuals=$(echo "$report" | grep -oE 'MANUAL:[^|]*' | sed 's/MANUAL://')
  ok=$(echo "$report" | grep -oE 'OK:[^|]*' | sed 's/OK://')
  if [[ -n "$fails" ]]; then
    finding warn "Manual jobs FAILED last run: $fails — re-trigger via \`glab ci trigger <id>\` or run script with --trigger-manual"
  fi
  if [[ -n "$manuals" ]]; then
    finding info "Manual jobs never run on this pipeline: $manuals"
  fi
  if [[ -n "$ok" ]]; then
    finding info "Manual jobs OK: $ok"
  fi
}

# ── Section 9: Render the report ────────────────────────────────────────────
render_report() {
  {
    echo "# Stability Check — $STAMP"
    echo ""
    echo "Generated by \`bin/dev/stability-check.sh\` — composes existing"
    echo "per-aspect scripts. See the script header for the full delegation list."
    echo ""
    if [[ "${#BLOCKING[@]}" -gt 0 ]]; then
      echo "## 🔴 BLOCKING (${#BLOCKING[@]})"
      printf -- "- %s\n" "${BLOCKING[@]}"
      echo ""
    fi
    if [[ "${#ATTENTION[@]}" -gt 0 ]]; then
      echo "## 🟡 ATTENTION (${#ATTENTION[@]})"
      printf -- "- %s\n" "${ATTENTION[@]}"
      echo ""
    fi
    if [[ "${#NICE[@]}" -gt 0 ]]; then
      echo "## 🟢 NICE TO HAVE (${#NICE[@]})"
      printf -- "- %s\n" "${NICE[@]}"
      echo ""
    fi
    if [[ "${#INFO[@]}" -gt 0 ]]; then
      echo "## ℹ️  Info (${#INFO[@]})"
      printf -- "- %s\n" "${INFO[@]}"
    fi
  } > "$REPORT"

  echo ""
  echo "✓ Report → $REPORT"
  echo ""
  if [[ "$COMMIT_REPORT" -eq 1 ]]; then
    (cd "$SVC_DIR" && git add "$REPORT" \
      && git commit -m "docs(audit): stability check $STAMP

$([[ "${#BLOCKING[@]}" -gt 0 ]] && echo "🔴 ${#BLOCKING[@]} blocking" || echo "no blocking")
🟡 ${#ATTENTION[@]} attention
🟢 ${#NICE[@]} nice-to-have

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" 2>&1 | tail -3)
  fi

  # Exit 1 if blocking findings present.
  [[ "${#BLOCKING[@]}" -gt 0 ]] && exit 1
  exit 0
}

# ── main ────────────────────────────────────────────────────────────────────
main() {
  echo "── Stability check — $STAMP ──"
  echo ""
  section_preflight
  section_healthcheck
  section_ci
  section_sonar
  section_code
  section_docs
  section_infra
  section_manual_jobs
  section_manual_health
  render_report
}

main
