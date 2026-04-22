#!/usr/bin/env bash
# bin/dev/sections/delta.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_delta
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

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

