#!/usr/bin/env bash
# bin/dev/sections/perf.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_bundle_size, section_lighthouse
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

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
    # BLOCKING absolute thresholds — independent of delta, flagged
    # regardless of whether the score regressed since last run. The
    # numbers below are calibrated to the project's current floor:
    # anything below them is noticeably bad UX, not just measurement
    # wiggle. Adjust the floors if a major UX overhaul changes the
    # baseline (e.g. switching to a heavier framework would push perf
    # down — bump the floor accordingly to avoid noise).
    if [[ "$perf" -lt 50 ]]; then
      finding block "Lighthouse: performance score $perf < 50 (user-perceptible slowness — LCP/TBT territory)"
    fi
    if [[ "$a11y" -lt 80 ]]; then
      finding block "Lighthouse: accessibility score $a11y < 80 (WCAG fails likely — colour contrast, missing labels, keyboard traps)"
    fi
    if [[ "$bp" -lt 80 ]]; then
      finding warn "Lighthouse: best-practices score $bp < 80 (HTTPS / mixed content / deprecated APIs / console errors)"
    fi
    if [[ "$seo" -lt 80 ]]; then
      finding warn "Lighthouse: SEO score $seo < 80 (missing meta tags / robots / structured data)"
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

