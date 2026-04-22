#!/usr/bin/env bash
# bin/dev/sections/infra.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_infra, section_helm_lint, section_terraform
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

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

# ── Section 5f-ter: Helm chart lint when deploy/helm/** exists ────────────
# Why: when the project starts shipping Helm charts (today there are
# none — only kustomize overlays — but the Java/cluster trajectory
# regularly proposes Helm), `helm lint` catches templating errors
# before they hit `helm install` and blow up the cluster. The check
# is a no-op in repos without a chart, so leaving it on costs nothing.
section_helm_lint() {
  echo "▸ Helm chart lint (when deploy/helm/** exists)…"
  if ! command -v helm &>/dev/null; then
    finding info "helm: binary not installed — skip (brew install helm)"
    return
  fi
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    if [[ ! -d "$repo/deploy/helm" ]]; then continue; fi
    while IFS= read -r chart; do
      [[ -z "$chart" ]] && continue
      local errs
      errs=$( (cd "$repo" && helm lint "$chart" 2>&1) | grep -cE "^Error:|\[ERROR\]" || echo 0)
      if [[ "$errs" -gt 0 ]]; then
        finding fail "$name: helm lint $chart — $errs error(s)"
      fi
    done < <( (cd "$repo" && find deploy/helm -name Chart.yaml -exec dirname {} \; 2>/dev/null) || true)
  done
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

