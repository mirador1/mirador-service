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
# Sections (each one independent — failure in one doesn't kill the rest):
#   1.  pre-flight             — git/branch hygiene + Docker disk pressure
#   1b. hooks-installed        — lefthook bypass detection (.git/hooks/pre-commit)
#   1c. dep-cache-size         — node_modules >2GB / .m2 >5GB warnings
#   2.  healthcheck            — delegates to bin/dev/healthcheck-all.sh
#   3.  ci                     — LATEST main blocks; last 5 = trend (info)
#   4.  sonar                  — refreshes both projects via mvn + npx
#   5.  code                   — `any`, silent handlers, empty catches
#   5b. stale-todos            — TODO/FIXME >30 days old (git blame)
#   5c. cve                    — npm audit (high/critical)
#   5c2.trivy-delta            — Trivy fs scan, NEW HIGH/CRITICAL only vs last run
#   5d. bundle-size            — UI dist/stats.json delta vs previous
#   5d2.lighthouse             — Lighthouse score delta on :4200 (skip if UI down)
#   5e. skipped-tests          — @Disabled (svc) + xit/.skip (UI)
#   5f. sonar-freshness        — local Sonar projects analyzed >7d ago
#   5g. java-logging           — System.out.print / printStackTrace
#   5h. ui-console             — console.* in src/ (telemetry sink excluded)
#   6.  docs                   — broken README links + root file budget (≤15)
#   6b. adr-sequence           — gaps in ADR numbering
#   7.  infra                  — mirror gap, open MRs, allow_failure inventory
#   8.  manual-jobs            — optional `--trigger-manual` triggers safe set
#   8b. manual-job-health      — last-run status of watched manual jobs
#   9a. delta                  — BLOCKING/ATTENTION trend vs previous report
#
# Output: docs/audit/stability-YYYY-MM-DD-HHMM.md (last 10 + daily snapshot
# kept; older intra-day reports auto-pruned). Commit history of these reports
# is the long-term trend timeline.
#
# Usage:
#   bin/dev/stability-check.sh                  # report only
#   bin/dev/stability-check.sh --trigger-manual # also run manual CI jobs
#   bin/dev/stability-check.sh --skip-sonar     # skip local Sonar refresh
#   bin/dev/stability-check.sh --commit         # auto-commit the report
#   bin/dev/stability-check.sh --tag-on-green   # commit + tag both repos
#                                                # if no 🔴 BLOCKING (per
#                                                # CLAUDE.md "Tag every
#                                                # green stability checkpoint")
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
TAG_ON_GREEN=0
for arg in "$@"; do
  case "$arg" in
    --trigger-manual) TRIGGER_MANUAL=1 ;;
    --skip-sonar)     SKIP_SONAR=1 ;;
    --commit)         COMMIT_REPORT=1 ;;
    --tag-on-green)   TAG_ON_GREEN=1; COMMIT_REPORT=1 ;;
    *) echo "unknown flag: $arg"; exit 2 ;;
  esac
done

STAMP="$(date +%Y-%m-%d-%H%M)"
START_TS=$(date +%s)   # for "Section 9b: script duration" trend
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

# ── Section 5b: Stale TODO/FIXME (>30 days old) ─────────────────────────────
# Surfaces tech debt comments that have been in the codebase for over a
# month — either they're real ongoing work (move to TASKS.md) or stale
# notes nobody owns (delete). Uses `git blame` for accurate authoring date,
# only checks first 50 hits per repo to keep runtime sub-second.
section_stale_todos() {
  echo "▸ Stale TODO/FIXME (>30 days)…"
  local cutoff
  cutoff=$(date -v-30d +%s 2>/dev/null || date -d '30 days ago' +%s)
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    local stale_count=0
    cd "$repo"
    # First pass: get up to 50 candidate lines.
    while IFS=: read -r file line _; do
      [[ -z "$file" || -z "$line" ]] && continue
      # git blame returns "%at" (author timestamp) for that line.
      local ts
      ts=$(git blame -L "$line,$line" --porcelain "$file" 2>/dev/null \
        | grep "^author-time " | awk '{print $2}' | head -1)
      if [[ -n "$ts" && "$ts" -lt "$cutoff" ]]; then
        stale_count=$((stale_count + 1))
      fi
    done < <( (grep -rn -E "TODO|FIXME|XXX" --include="*.ts" --include="*.java" \
      --exclude-dir=node_modules --exclude-dir=target src 2>/dev/null || true) | head -50)
    if [[ "$stale_count" -gt 0 ]]; then
      finding warn "$name: $stale_count TODO/FIXME comment(s) older than 30 days — move to TASKS.md or delete"
    fi
  done
}

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

# ── Section 5d: UI bundle size delta vs previous run ────────────────────────
# Reads the latest stats.json (if produced by `ng build --stats-json`),
# compares total initial JS to the previous stability-check report.
# >5% growth = 🟡 ATTENTION (signals an unintended dep / lazy-load
# regression). Stores the running max in docs/audit/.bundle-size.txt
# for trend detection.
section_bundle_size() {
  echo "▸ UI bundle size delta…"
  local stats="$UI_DIR/dist/mirador-ui/stats.json"
  if [[ ! -f "$stats" ]]; then
    finding info "UI bundle delta: stats.json not found (run \`npm run build -- --stats-json\` first)"
    return
  fi
  local current_kb prev_kb
  # Angular @angular/build (esbuild) emits stats.json with `outputs` dict
  # (one entry per file). Webpack-style `assets` array doesn't exist.
  # Sum bytes of every .js output for the total bundle footprint.
  current_kb=$(python3 -c "
import json
d = json.load(open('$stats'))
total = sum(info.get('bytes', 0) for path, info in d.get('outputs', {}).items() if path.endswith('.js'))
print(total // 1024)" 2>/dev/null || echo "0")
  local trend_file="$REPORT_DIR/.bundle-size.txt"
  if [[ -f "$trend_file" ]]; then
    prev_kb=$(cat "$trend_file" 2>/dev/null || echo "0")
    if [[ "$prev_kb" -gt 0 ]]; then
      local delta_pct
      delta_pct=$(python3 -c "print(int((${current_kb} - ${prev_kb}) * 100 / ${prev_kb}))")
      if [[ "$delta_pct" -gt 5 ]]; then
        finding warn "UI bundle: ${current_kb} KB (${delta_pct}% larger than previous ${prev_kb} KB)"
      else
        finding info "UI bundle: ${current_kb} KB (${delta_pct}% delta vs ${prev_kb} KB)"
      fi
    fi
  else
    finding info "UI bundle: ${current_kb} KB (baseline run, no previous to compare)"
  fi
  echo "$current_kb" > "$trend_file"
}

# ── Section 5d2: Lighthouse score regression vs previous run ───────────────
# Runs Lighthouse against the UI's dev server (or skips if nothing on :4200)
# and compares the 4 category scores (perf, a11y, best-practices, SEO) to
# the previous run stored at $REPORT_DIR/.lighthouse-last.json. Flags any
# score drop of >5 points. A performance score below 50 is BLOCKING because
# it correlates with real user-perceived slowness (LCP, TBT).
#
# Why a dedicated baseline file next to audit reports: the initial audit
# produced docs/audit/lighthouse.{html,json} in the UI repo; those files
# represent a one-shot snapshot. This section needs a MOVING baseline
# (the previous stability-check run) — hence the separate dotfile.
# Runtime: ~30s when UI is up (desktop preset), 0s when skipped.
section_lighthouse() {
  echo "▸ Lighthouse score delta (UI on :4200)…"
  # Fast check: is the UI actually up? curl -f fails on non-2xx; --max-time
  # keeps a hung port from blocking the whole stability check.
  if ! curl -f -s --max-time 3 http://localhost:4200 >/dev/null 2>&1; then
    finding info "Lighthouse: UI not on :4200 — skip (start with \`npm start\` in mirador-ui)"
    return
  fi
  if ! command -v npx >/dev/null 2>&1; then
    finding info "Lighthouse: npx binary missing — skip"
    return
  fi
  local out=/tmp/lh-current.json
  # `--quiet` suppresses the progress log; `--chrome-flags=--headless=new`
  # is required on macOS to avoid the GUI Chrome window popping up.
  # Using lighthouse@12 (current major at time of writing) to keep output
  # schema stable across runs.
  if ! npx --yes lighthouse@12 http://localhost:4200 \
       --preset=desktop \
       --output=json \
       --output-path="$out" \
       --chrome-flags="--headless=new --no-sandbox" \
       --quiet >/dev/null 2>&1; then
    finding warn "Lighthouse: run failed — check \`npx lighthouse http://localhost:4200\` manually"
    return
  fi
  # Extract 4 scores as integers (Lighthouse emits 0.0–1.0 floats).
  local cur_scores
  cur_scores=$(python3 -c "
import json
d = json.load(open('$out'))
cats = d.get('categories', {})
def pct(k):
    v = cats.get(k, {}).get('score')
    return int(round(v * 100)) if v is not None else None
perf = pct('performance')
a11y = pct('accessibility')
bp   = pct('best-practices')
seo  = pct('seo')
print(f'{perf}|{a11y}|{bp}|{seo}')" 2>/dev/null || echo "||||")
  local perf a11y bp seo
  IFS='|' read -r perf a11y bp seo <<< "$cur_scores"
  [[ -z "$perf" || ! "$perf" =~ ^[0-9]+$ ]] && {
    finding warn "Lighthouse: could not parse scores from $out"
    return
  }
  # Compare to previous baseline if present.
  local baseline="$REPORT_DIR/.lighthouse-last.json"
  if [[ -f "$baseline" ]]; then
    local prev_perf prev_a11y prev_bp prev_seo
    local prev
    prev=$(python3 -c "
import json
try:
    d = json.load(open('$baseline'))
    print(f\"{d.get('performance',0)}|{d.get('accessibility',0)}|{d.get('best-practices',0)}|{d.get('seo',0)}\")
except: print('0|0|0|0')")
    IFS='|' read -r prev_perf prev_a11y prev_bp prev_seo <<< "$prev"
    local d_perf=$((perf - prev_perf))
    local d_a11y=$((a11y - prev_a11y))
    local d_bp=$((bp - prev_bp))
    local d_seo=$((seo - prev_seo))
    # A drop > 5 points on any category is worth surfacing; smaller wiggle
    # is just Lighthouse measurement noise on a local machine.
    local regressed=""
    [[ "$d_perf" -lt -5 ]] && regressed="${regressed}perf ${prev_perf}→${perf} "
    [[ "$d_a11y" -lt -5 ]] && regressed="${regressed}a11y ${prev_a11y}→${a11y} "
    [[ "$d_bp"   -lt -5 ]] && regressed="${regressed}bp ${prev_bp}→${bp} "
    [[ "$d_seo"  -lt -5 ]] && regressed="${regressed}seo ${prev_seo}→${seo} "
    if [[ -n "$regressed" ]]; then
      finding warn "Lighthouse regression (>5 pts): $regressed"
    else
      finding info "Lighthouse: perf=$perf a11y=$a11y bp=$bp seo=$seo (Δ $d_perf/$d_a11y/$d_bp/$d_seo)"
    fi
    # BLOCKING: perf < 50 is real user-facing slowness (LCP/TBT territory),
    # independent of delta — flag regardless of whether it regressed.
    if [[ "$perf" -lt 50 ]]; then
      finding block "Lighthouse: performance score $perf < 50 (user-perceptible slowness)"
    fi
  else
    finding info "Lighthouse: baseline recorded (perf=$perf a11y=$a11y bp=$bp seo=$seo)"
  fi
  # Persist current scores for next run's delta.
  python3 -c "
import json
json.dump({'performance': $perf, 'accessibility': $a11y,
           'best-practices': $bp, 'seo': $seo},
          open('$baseline', 'w'))" 2>/dev/null || true
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

# ── Section 5g: System.out / printStackTrace in Java (Logger required) ─────
# Spring Boot apps log via SLF4J/Logback. `System.out.println` and
# `e.printStackTrace()` bypass that path: no log level, no MDC trace ID,
# no structured output (Loki can't parse). Each occurrence is technical
# debt waiting to surface as a missing log line in a production incident.
section_java_logging() {
  echo "▸ Java logging hygiene…"
  local sout pst
  sout=$( (cd "$SVC_DIR" && grep -rn "System\.out\.print\|System\.err\.print" \
    --include="*.java" src/main 2>/dev/null || true) | wc -l | tr -d ' ')
  pst=$( (cd "$SVC_DIR" && grep -rn "\.printStackTrace()" \
    --include="*.java" src/main 2>/dev/null || true) | wc -l | tr -d ' ')
  if [[ "$sout" -gt 0 ]]; then
    finding warn "svc: $sout System.out/err call(s) in src/main — replace with SLF4J Logger"
  fi
  if [[ "$pst" -gt 0 ]]; then
    finding warn "svc: $pst .printStackTrace() call(s) in src/main — log via SLF4J + ex.getMessage()"
  fi
}

# ── Section 5h: console.log left in UI prod code ──────────────────────────
# Same idea as Java's System.out — anything in src/ that ships to the
# browser should not log to console (no log level, no structured output,
# pollutes the user's devtools). Test specs are exempt.
section_ui_console() {
  echo "▸ UI console.log hygiene…"
  local clog
  # Exclusions:
  #   - src/app/core/telemetry/  → central logging service (uses console as
  #     final sink, by design)
  #   - src/main.ts              → bootstrap fallback (runs before DI is up)
  #   - lines starting with * or //  → comments / docstrings
  clog=$( (cd "$UI_DIR" && grep -rnE "console\.(log|debug|warn|error)" \
    --include="*.ts" --exclude="*.spec.ts" --exclude="main.ts" \
    --exclude-dir=node_modules --exclude-dir=telemetry src 2>/dev/null \
    | grep -vE ":\s*\*|:\s*//" || true) | wc -l | tr -d ' ')
  if [[ "$clog" -gt 3 ]]; then
    finding warn "UI: $clog console.* call(s) in src/ — route through TelemetryService"
  elif [[ "$clog" -gt 0 ]]; then
    finding info "UI: $clog console.* call(s) (≤3 acceptable; telemetry sink + main bootstrap excluded)"
  fi
}

# ── Section 1b: Pre-commit hooks installed (lefthook) ──────────────────────
# A repo with a lefthook config but no .git/hooks/pre-commit silently
# bypasses every lint/format/secret-scan we configured. Easy to forget
# after a fresh `git clone` (lefthook needs an explicit `lefthook
# install`). As of 2026-04-20 the config can live at either ./lefthook.yml
# (legacy) or ./.config/lefthook.yml (root-hygiene move); both probed.
section_hooks_installed() {
  echo "▸ Pre-commit hooks installed…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    if [[ ! -f "$repo/lefthook.yml" && ! -f "$repo/.config/lefthook.yml" ]]; then continue; fi
    if [[ ! -f "$repo/.git/hooks/pre-commit" ]]; then
      finding warn "$name: lefthook config present but .git/hooks/pre-commit missing — \`lefthook install\`"
    fi
  done
}

# ── Section 1c: node_modules / .m2 size sanity ─────────────────────────────
# Bloated dependency caches are usually safe to ignore but >2 GB indicates
# either a recent breaking dep bump (clean install would help) or
# accumulated cruft from `npm ci` race conditions across CI variants.
section_dep_cache_size() {
  echo "▸ Dependency cache sizes…"
  if [[ -d "$UI_DIR/node_modules" ]]; then
    local nm_mb
    nm_mb=$(du -sm "$UI_DIR/node_modules" 2>/dev/null | awk '{print $1}')
    if [[ "$nm_mb" -gt 2048 ]]; then
      finding warn "UI node_modules: ${nm_mb} MB — consider \`rm -rf node_modules && npm ci\`"
    fi
  fi
  if [[ -d "$SVC_DIR/.m2" ]]; then
    local m2_mb
    m2_mb=$(du -sm "$SVC_DIR/.m2" 2>/dev/null | awk '{print $1}')
    if [[ "$m2_mb" -gt 5120 ]]; then
      finding warn "svc local .m2: ${m2_mb} MB — consider \`rm -rf .m2 && mvn package -DskipTests\`"
    fi
  fi
}

# ── Section 6c: Terraform syntax + fmt validation ──────────────────────────
# Catches HCL regressions across all 4 provider modules (gcp, aws, azure,
# scaleway) without running `terraform plan` (which would need cloud auth).
# Fast: `validate` + `fmt -check` are purely local HCL parsing, ~1s per
# module. Skipped if `terraform` binary isn't installed (demo-friendly).
section_terraform() {
  echo "▸ Terraform validate + fmt…"
  if ! command -v terraform >/dev/null 2>&1; then
    finding info "terraform binary not installed — skip (brew install terraform)"
    return
  fi
  local invalid=""
  local unformatted=""
  for mod in "$SVC_DIR/deploy/terraform"/{gcp,aws,azure,scaleway}; do
    [[ ! -d "$mod" ]] && continue
    local mod_name=$(basename "$mod")
    # `init -backend=false` avoids touching remote state. Quiet on success.
    if ! (cd "$mod" && silent terraform init -backend=false -input=false \
          && terraform validate >/dev/null 2>&1); then
      invalid="${invalid}${mod_name} "
    fi
    if ! (cd "$mod" && silent terraform fmt -check -diff); then
      unformatted="${unformatted}${mod_name} "
    fi
  done
  if [[ -n "$invalid" ]]; then
    finding warn "Terraform validate failed: $invalid— \`terraform validate\` in each"
  fi
  if [[ -n "$unformatted" ]]; then
    finding warn "Terraform fmt drift: $unformatted— \`terraform fmt -recursive deploy/terraform/\`"
  fi
  if [[ -z "$invalid" && -z "$unformatted" ]]; then
    finding info "Terraform: all 4 modules validate + formatted"
  fi
}

# ── Section 7b: Pinned upstream versions (no :latest, no floating tags) ───
# CLAUDE.md "Pin every upstream reference. No floating tags." — when an
# upstream ships a breaking change silently, floating tags turn it into a
# production outage. Scans compose files and Dockerfiles for :latest or
# bare `image: foo` without tag.
section_pinned_versions() {
  echo "▸ Pinned upstream versions…"
  local floating=""
  # Explicit file list — bash globs under `set -o pipefail` combined with
  # the double grep pipeline can return non-zero when the second grep
  # filters everything, killing the script. Use a plain loop with
  # defensive `|| true` inside.
  local files=(
    "$SVC_DIR/docker-compose.yml"
    "$SVC_DIR/docker-compose.observability.yml"
    "$SVC_DIR/build/Dockerfile"
  )
  for f in "${files[@]}"; do
    [[ ! -f "$f" ]] && continue
    # Only flag literal `:latest` tags — bare `image: foo` is often a
    # build-context artefact (our own local image) which shouldn't be
    # tagged; too noisy to enforce here.
    if grep -qE "^\s*(image|FROM)\s+\S*:latest\b" "$f" 2>/dev/null; then
      floating="${floating}$(basename "$f") "
    fi
  done
  if [[ -n "$floating" ]]; then
    finding warn "Floating :latest tags in: $floating— pin per CLAUDE.md"
  else
    finding info "All upstream images pinned (no :latest)"
  fi
}

# ── Section 7c: .env key drift vs .env.example (onboarding hygiene) ────────
# If .env has keys that .env.example doesn't document, a new contributor
# can't `cp .env.example .env` and get a working local stack — they'll hit
# a runtime error later when the code reads an undocumented env var.
section_env_drift() {
  echo "▸ .env / .env.example drift…"
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    [[ ! -f "$repo/.env.example" || ! -f "$repo/.env" ]] && continue
    local missing
    missing=$(comm -23 \
      <(grep -oE "^[A-Z_]+" "$repo/.env" 2>/dev/null | sort -u) \
      <(grep -oE "^[A-Z_]+" "$repo/.env.example" 2>/dev/null | sort -u) \
      | tr '\n' ' ')
    if [[ -n "$missing" ]]; then
      finding warn "$name .env keys missing from .env.example: $missing"
    fi
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

# ── Section 9a: Delta vs previous report (trend tracking) ──────────────────
# Compare BLOCKING/ATTENTION counts against the previous run's report.
# Helps surface regressions ("we used to have 3 attention, now 7"),
# even when no individual finding is new — the cumulative trend is the
# shippable signal.
section_delta() {
  local prev
  prev=$(ls -1 "$REPORT_DIR"/stability-*.md 2>/dev/null | grep -v "$STAMP" | tail -1)
  if [[ -z "$prev" ]]; then
    finding info "Delta: no previous report to compare (baseline run)"
    return
  fi
  local prev_block prev_attn cur_block cur_attn
  prev_block=$(grep -oE "🔴 BLOCKING \([0-9]+\)" "$prev" 2>/dev/null \
    | grep -oE "[0-9]+" || echo "0")
  prev_attn=$(grep -oE "🟡 ATTENTION \([0-9]+\)" "$prev" 2>/dev/null \
    | grep -oE "[0-9]+" || echo "0")
  prev_block=${prev_block:-0}
  prev_attn=${prev_attn:-0}
  cur_block=${#BLOCKING[@]}
  cur_attn=${#ATTENTION[@]}
  local delta_block=$((cur_block - prev_block))
  local delta_attn=$((cur_attn - prev_attn))
  local trend="🔴 ${prev_block}→${cur_block} (Δ${delta_block}), 🟡 ${prev_attn}→${cur_attn} (Δ${delta_attn})"
  if [[ "$delta_block" -gt 0 ]]; then
    finding warn "Trend regressed: $trend (vs $(basename "$prev"))"
  elif [[ "$delta_attn" -gt 0 ]]; then
    finding info "Trend: $trend (attention growing)"
  else
    finding info "Trend: $trend (steady or improving)"
  fi
}

# ── Section 9: Render the report ────────────────────────────────────────────
render_report() {
  local end_ts duration_s
  end_ts=$(date +%s)
  duration_s=$((end_ts - START_TS))

  # Retention: keep last 10 reports + the daily snapshot (last of each
  # YYYY-MM-DD). Older intra-day reports are deleted to avoid the
  # docs/audit/ dir growing unbounded — 14 reports already today as
  # the script iterates fast. Trend tracking uses the last report so
  # this doesn't break section_delta.
  if [[ -d "$REPORT_DIR" ]]; then
    cd "$REPORT_DIR"
    # Keep last 10 + one per day. Implemented via Python (ls + grep
    # would mis-handle date sorting across month boundaries).
    python3 -c "
import os, re, collections
files = sorted([f for f in os.listdir('.') if re.match(r'stability-.*\.md', f)])
keep_last_10 = set(files[-10:])
by_day = collections.defaultdict(list)
for f in files:
    day = f.split('-')[1] + '-' + f.split('-')[2] + '-' + f.split('-')[3]
    by_day[day].append(f)
keep_daily = {sorted(v)[-1] for v in by_day.values()}
for f in files:
    if f not in keep_last_10 and f not in keep_daily:
        os.remove(f)
        print(f'  - retention: removed {f}')" 2>/dev/null || true
    cd - >/dev/null
  fi

  {
    echo "# Stability Check — $STAMP"
    echo ""
    echo "Generated by \`bin/dev/stability-check.sh\` (${duration_s}s) — composes"
    echo "existing per-aspect scripts. See the script header for delegation."
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
    # `git add -A docs/audit/` so retention-deleted files are staged for
    # removal too. `git add <REPORT>` alone misses deletions.
    (cd "$SVC_DIR" && git add -A docs/audit/ \
      && git commit -m "docs(audit): stability check $STAMP

$([[ "${#BLOCKING[@]}" -gt 0 ]] && echo "🔴 ${#BLOCKING[@]} blocking" || echo "no blocking")
🟡 ${#ATTENTION[@]} attention
🟢 ${#NICE[@]} nice-to-have

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>" 2>&1 | tail -3)
  fi

  # ── Auto-tag on green (per CLAUDE.md "Tag every green stability checkpoint")
  # Tag only when no 🔴 BLOCKING and the user opted in via --tag-on-green.
  # Bumps PATCH from the latest v<MAJOR>.<MINOR>.<PATCH> tag in svc.
  if [[ "$TAG_ON_GREEN" -eq 1 && "${#BLOCKING[@]}" -eq 0 ]]; then
    local last_tag next_tag
    last_tag=$( (cd "$SVC_DIR" && git tag --sort=-v:refname 2>/dev/null) \
      | grep -E "^v[0-9]+\.[0-9]+\.[0-9]+$" | head -1)
    if [[ -z "$last_tag" ]]; then
      next_tag="v0.1.0"
    else
      # Bump PATCH (e.g. v0.1.7 → v0.1.8).
      next_tag=$(echo "$last_tag" | python3 -c "
import sys
parts = sys.stdin.read().strip().lstrip('v').split('.')
parts[2] = str(int(parts[2]) + 1)
print('v' + '.'.join(parts))")
    fi
    echo "▸ Tagging green checkpoint $next_tag on both repos…"
    for repo in "$SVC_DIR" "$UI_DIR"; do
      (cd "$repo" && git tag -a "$next_tag" \
         -m "Stability checkpoint — see $REPORT" 2>&1 | tail -2)
      (cd "$repo" && git push origin "$next_tag" 2>&1 | tail -2)
      (cd "$repo" && git remote | grep -q github \
        && git push github "$next_tag" 2>&1 | tail -2 || true)
    done
    echo "✓ Tagged $next_tag (svc + UI, both remotes)"
  elif [[ "$TAG_ON_GREEN" -eq 1 ]]; then
    echo "✗ Skipping tag: ${#BLOCKING[@]} blocking finding(s) present."
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
  section_hooks_installed
  section_dep_cache_size
  section_healthcheck
  section_ci
  section_sonar
  section_code
  section_stale_todos
  section_cve
  section_trivy_delta
  section_bundle_size
  section_lighthouse
  section_skipped_tests
  section_sonar_freshness
  section_java_logging
  section_ui_console
  section_docs
  section_adr_sequence
  section_terraform
  section_pinned_versions
  section_env_drift
  section_infra
  section_manual_jobs
  section_manual_health
  section_delta
  render_report
}

main
