#!/usr/bin/env bash
# bin/dev/sections/code.sh — extracted 2026-04-22 from stability-check.sh (Phase B-3)
# Functions: section_code, section_stale_todos, section_java_logging, section_ui_console, section_file_length, section_pinned_versions
# Sourced by ../stability-check.sh; relies on globals (SVC_DIR, UI_DIR,
# STAMP, BLOCKING[], ATTENTION[], NICE[], INFO[]) + helpers (finding, silent).

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

# ── Section 5b: Stale TODO/FIXME (>30 days old) ─────────────────────────────
# Surfaces tech debt comments that have been in the codebase for over a
# month — either they're real ongoing work (move to TASKS.md) or stale
# notes nobody owns (delete). Uses `git blame` for accurate authoring date,
# only checks first 50 hits per repo to keep runtime sub-second.
section_stale_todos() {
  echo "▸ Stale TODO/FIXME (>30 days, by author)…"
  local cutoff
  cutoff=$(date -v-30d +%s 2>/dev/null || date -d '30 days ago' +%s)
  for repo in "$SVC_DIR" "$UI_DIR"; do
    local name=$(basename "$repo")
    local stale_count=0
    declare -A by_author=()
    cd "$repo"
    # First pass: get up to 50 candidate lines.
    while IFS=: read -r file line _; do
      [[ -z "$file" || -z "$line" ]] && continue
      # git blame --porcelain emits "author-time <ts>" + "author <name>"
      # for the line. Capture both in one blame invocation.
      local blame ts author
      blame=$(git blame -L "$line,$line" --porcelain "$file" 2>/dev/null || true)
      [[ -z "$blame" ]] && continue
      ts=$(echo "$blame" | awk '/^author-time /{print $2; exit}')
      author=$(echo "$blame" | awk '/^author /{$1=""; sub(/^ /,""); print; exit}')
      if [[ -n "$ts" && "$ts" -lt "$cutoff" ]]; then
        stale_count=$((stale_count + 1))
        # Tally per author so the report shows WHO has the most
        # outstanding stale TODOs. Useful on multi-author repos to
        # nudge ownership; on single-author projects this just
        # confirms the same author keeps rolling forward TODOs.
        if [[ -n "$author" ]]; then
          by_author["$author"]=$((${by_author["$author"]:-0} + 1))
        fi
      fi
    done < <( (grep -rn -E "TODO|FIXME|XXX" --include="*.ts" --include="*.java" \
      --exclude-dir=node_modules --exclude-dir=target src 2>/dev/null || true) | head -50)
    if [[ "$stale_count" -gt 0 ]]; then
      # Build a "Author1: N, Author2: M" string sorted by count desc.
      local breakdown=""
      if [[ "${#by_author[@]}" -gt 0 ]]; then
        breakdown=$(for k in "${!by_author[@]}"; do echo "${by_author[$k]} $k"; done \
          | sort -rn | awk '{c=$1; $1=""; sub(/^ /,""); printf "%s: %d, ", $0, c}' \
          | sed 's/, $//')
        breakdown=" — by author: $breakdown"
      fi
      finding warn "$name: $stale_count TODO/FIXME comment(s) older than 30 days — move to TASKS.md or delete$breakdown"
    fi
    unset by_author 2>/dev/null || true
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

# ── Section 5h-bis: Feature-slice health snapshot (e-commerce + ML) ────────
# Per-slice LOC + test count for the 3 newer feature slices :
#   - com.mirador.order.*   (V8/V9 migrations, 2026-04-26)
#   - com.mirador.product.* (V7 migration, 2026-04-26)
#   - com.mirador.ml.*      (Phase B Customer Churn ONNX inference,
#                            shared ADR-0061, 2026-04-27)
#
# Surfaces a low-cost "is this slice still healthy" signal that the
# generic greps above can't (they aggregate across the whole code-base).
# Reports as info — not blocking — but worth scanning at every run.
section_feature_slices() {
  echo "▸ Feature-slice health snapshot…"
  for slice in order product ml; do
    local main_root="$SVC_DIR/src/main/java/com/mirador/$slice"
    local test_root="$SVC_DIR/src/test/java/com/mirador/$slice"
    if [ ! -d "$main_root" ]; then
      finding info "svc: slice $slice — no main src/ directory (skipped)"
      continue
    fi
    local main_loc test_loc main_count test_count
    main_loc=$( (find "$main_root" -name "*.java" -print0 2>/dev/null \
      | xargs -0 cat 2>/dev/null | wc -l | tr -d ' ') )
    test_loc=$( (find "$test_root" -name "*.java" -print0 2>/dev/null \
      | xargs -0 cat 2>/dev/null | wc -l | tr -d ' ') )
    main_count=$( (find "$main_root" -name "*.java" 2>/dev/null | wc -l | tr -d ' ') )
    test_count=$( (find "$test_root" -name "*.java" 2>/dev/null | wc -l | tr -d ' ') )
    finding info "svc: slice $slice — main ${main_count}f/${main_loc}L, test ${test_count}f/${test_loc}L"
    # Soft check : every slice should have at least 1 test file (catches
    # the case where someone shipped a feature without an accompanying
    # test class — unit-tests at the project level would still pass with
    # the existing JaCoCo gate, but the slice itself is naked).
    if [ "$test_count" -eq 0 ] && [ "$main_count" -gt 0 ]; then
      finding warn "svc: slice $slice has $main_count source file(s) but no tests — write at least one"
    fi
  done
}

# ── Section 5i: Hand-written files ≥ 1500 lines (split-now tier) ───────────
# Enforces the "File length hygiene" rule in ~/.claude/CLAUDE.md:
#
#   ≥ 1 000 lines → plan a split at next touch (NOT enforced here — it's a
#                   reviewer convention)
#   ≥ 1 500 lines → split NOW before any other change lands on it (THIS gate)
#
# Allowlisted files are documented one-per-line with WHY (length inherent,
# Phase B-X split ticket, auto-generated, etc.). Each Phase B split commit
# removes its entry. See docs/audit/quality-thresholds-2026-04-21.md for
# the current allowlist rationale + the sequencing plan.
section_file_length() {
  echo "▸ File length hygiene (≥1500 line gate)…"
  local threshold=1500
  # Allowlist entries: path suffix (relative to repo root), matched via
  # exact string containment. Comments in docs/audit/quality-thresholds-
  # 2026-04-21.md explain the rationale per entry. Keep this list SHORT;
  # every entry is tech debt with a Phase B ticket.
  local allowlist=(
    # svc — CI modularisation backlog (Phase B-2, ~3 h)
    '.gitlab-ci.yml'
    # svc — Maven monorepo; length is inherent (BOMs, profiles, 12 plugins)
    'pom.xml'
    # svc — Phase B-1 split target (QualityReportEndpoint → 7 parsers + aggregator)
    'src/main/java/com/mirador/observability/QualityReportEndpoint.java'
    # UI — Phase B-5 split target (1 child QualityPanelXxx component per panel)
    'src/app/features/obs/quality/quality.component.html'
    # UI — docs: auto-generated by openapi-typescript, ignored by lint/prettier
    'src/app/core/api/generated.types.ts'
    # UI — Lighthouse audit dump (generated); not hand-written
    'docs/audit/lighthouse.json'
    'docs/audit/lighthouse.html'
    # UI — OpenAPI snapshot (generated by `npm run gen:openapi-snapshot`,
    # Phase 2.3 D1 — source of truth is the backend /v3/api-docs endpoint)
    'docs/api/openapi.json'
    # Lock files — inherent length, generated by npm ci / mvn verify
    'package-lock.json'
    'package.json.lock'
    # svc — kube-prometheus-stack chart rendered YAML (generated)
    'kube-prom-stack-crds.yaml'
    'kube-prom-stack-rendered.yaml'
    # svc — OWASP dep-check NVD cache (auto-downloaded by mvn verify -Preport)
    '.owasp-data/publishedSuppressions.xml'
    # svc — terraform provider changelog (auto-installed by terraform init)
    '.terraform/providers/'
  )
  # Build the find command, scoped to hand-written source + doc extensions,
  # excluding generated + vendored trees aggressively.
  local -a offenders=()
  local line
  # awk trick: print "<lines> <path>" only for files at or above threshold,
  # minus the `total` pseudo-line emitted by wc.
  while IFS= read -r line; do
    local count path rel
    count=$(echo "$line" | awk '{print $1}')
    path=$(echo "$line" | awk '{print $2}')
    [[ "$path" == "total" ]] && continue
    [[ -z "$path" ]] && continue
    # Normalise to a path relative to whichever repo root it lives under
    # so the allowlist match is stable across svc/ + UI repos.
    rel="${path#./}"
    # Skip if any allowlist entry matches (substring match).
    local allowed=0
    for a in "${allowlist[@]}"; do
      if [[ "$rel" == *"$a"* ]]; then allowed=1; break; fi
    done
    if [[ "$allowed" -eq 0 ]]; then
      offenders+=("${count} ${rel}")
    fi
  done < <(
    (cd "$SVC_DIR" && find . -type f \
        \( -name '*.java' -o -name '*.ts' -o -name '*.tsx' -o -name '*.yaml' \
           -o -name '*.yml' -o -name '*.md' -o -name '*.sh' -o -name '*.sql' \
           -o -name '*.scss' -o -name '*.html' -o -name '*.xml' \) \
        -not -path './target/*' -not -path './.git/*' -not -path './node_modules/*' \
        -not -path './.claude/*' -not -path './docs/compodoc/*' \
        -not -path './docs/typedoc/*' \
        -exec wc -l {} + 2>/dev/null \
      | awk -v t=$threshold '$1 >= t') || true
    (cd "$UI_DIR" 2>/dev/null && find . -type f \
        \( -name '*.ts' -o -name '*.tsx' -o -name '*.yaml' -o -name '*.yml' \
           -o -name '*.md' -o -name '*.sh' -o -name '*.scss' -o -name '*.html' \
           -o -name '*.json' \) \
        -not -path './.angular/*' -not -path './.git/*' -not -path './node_modules/*' \
        -not -path './dist/*' -not -path './.claude/*' -not -path './docs/compodoc/*' \
        -not -path './docs/typedoc/*' \
        -exec wc -l {} + 2>/dev/null \
      | awk -v t=$threshold '$1 >= t') || true
  )
  if [[ ${#offenders[@]} -gt 0 ]]; then
    finding block "file length: ${#offenders[@]} hand-written file(s) ≥ ${threshold} lines outside allowlist — split per docs/audit/quality-thresholds-2026-04-21.md"
    for o in "${offenders[@]}"; do
      echo "    $o" >&2
    done
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

