#!/usr/bin/env bash
# =============================================================================
# bin/dev/regen-adr-index.sh — auto-regenerate the ADR flat index table.
#
# Mirador has 40+ ADRs. Keeping the flat-index table in docs/adr/README.md
# in sync by hand is the kind of chore that rots silently — every new ADR
# PR has to remember to add a row (and the row format is finicky: title
# escaping, superseded-by link, etc.). This script generates the correct
# table from the ADR files themselves.
#
# The generated block sits between marker comments in README.md:
#
#     <!-- ADR-INDEX:START -->
#     | ID | Status | Title |
#     |---|---|---|
#     | 0001 | Accepted | [Record architecture decisions](0001-record-architecture-decisions.md) |
#     ...
#     <!-- ADR-INDEX:END -->
#
# The rest of README.md (preamble, status snapshot, hierarchical index,
# footer) is hand-curated — only the flat table between the markers is
# rewritten.
#
# Usage:
#   bin/dev/regen-adr-index.sh              # print generated table to stdout
#   bin/dev/regen-adr-index.sh --in-place   # replace between markers in README.md
#   bin/dev/regen-adr-index.sh --check      # exit 1 if drift detected (CI mode)
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
ADR_DIR="${REPO_ROOT}/docs/adr"
README="${ADR_DIR}/README.md"
MARKER_START="<!-- ADR-INDEX:START -->"
MARKER_END="<!-- ADR-INDEX:END -->"

mode="print"
case "${1:-}" in
    --in-place) mode="in-place" ;;
    --check)    mode="check" ;;
    --help|-h)
        sed -n '/^# Usage:/,/^$/p' "$0" | sed 's/^# //; s/^#//'
        exit 0
        ;;
    "") ;;
    *)
        echo "Unknown arg: $1" >&2
        exit 2
        ;;
esac

# -----------------------------------------------------------------------------
# Generate the flat-index table to stdout.
# -----------------------------------------------------------------------------
generate_table() {
    printf '| ID | Status | Title |\n'
    printf '|---|---|---|\n'

    for f in "${ADR_DIR}"/[0-9][0-9][0-9][0-9]-*.md; do
        [ -f "$f" ] || continue
        local filename id title status sup_by_link sup_by_file link_suffix

        filename="$(basename "$f")"
        id="${filename:0:4}"

        # Skip the template file (0000-template.md) — it's a scaffold, not
        # a real ADR. Recognised by its placeholder title "ADR-NNNN" with
        # literal N's, which no real ADR ever uses.
        [ "$id" = "0000" ] && continue

        # Title — strip the leading "# ADR-NNNN — " or "# ADR-NNNN: " from the
        # first heading so the link text is just the human description.
        title="$(head -1 "$f" \
            | sed -E 's/^#[[:space:]]*//' \
            | sed -E 's/^ADR-[0-9]{4}[[:space:]]*[—:-]+[[:space:]]*//' \
            | sed -E 's/^ADR-[0-9]{4}:[[:space:]]*//')"

        # Status — accepts both "- Status: X" and "- **Status**: X" shapes.
        # `|| true` shields against grep exit 1 when an ADR has a non-standard
        # status block (e.g. French "Statut" or none) ; under `set -euo pipefail`
        # a grep miss would otherwise abort the whole script and silently
        # truncate the table at the first offending ADR.
        status="$( { grep -m1 -iE '^-[[:space:]]+(\*\*)?Status(\*\*)?:' "$f" || true; } \
            | sed -E 's/^-[[:space:]]+(\*\*)?Status(\*\*)?:[[:space:]]*//' \
            | sed -E 's/\*\*//g' \
            | awk '{print $1}')"
        # Default when no Status line found — keeps the row visible in the table.
        [ -z "$status" ] && status="Unknown"

        # Superseded-by link — only when status is "Superseded" AND the ADR
        # body carries a "Superseded by [ADR-XXXX](XXXX-slug.md)" reference.
        link_suffix=""
        if [ "$status" = "Superseded" ]; then
            local sup_line
            sup_line="$(grep -iE 'Superseded by' "$f" | head -1 || true)"
            sup_by_link="$(printf '%s' "$sup_line" | grep -oE 'ADR-[0-9]{4}' | head -1 || true)"
            sup_by_file="$(printf '%s' "$sup_line" | grep -oE '[0-9]{4}-[a-z0-9-]+\.md' | head -1 || true)"
            if [ -n "$sup_by_link" ] && [ -n "$sup_by_file" ]; then
                link_suffix=" → [${sup_by_link}](${sup_by_file})"
            fi
        fi

        printf '| %s | %s | [%s](%s)%s |\n' "$id" "$status" "$title" "$filename" "$link_suffix"
    done
}

# -----------------------------------------------------------------------------
# --check mode: diff generated vs current content between the markers, exit 1
# if they differ. CI / stability-check uses this to detect drift.
# -----------------------------------------------------------------------------
check_drift() {
    if ! grep -qF "$MARKER_START" "$README" || ! grep -qF "$MARKER_END" "$README"; then
        echo "ERROR: ADR-INDEX markers missing from $README" >&2
        echo "Add these two lines around the flat-index table:" >&2
        echo "  $MARKER_START" >&2
        echo "  $MARKER_END" >&2
        exit 2
    fi

    local current expected
    current="$(awk -v s="$MARKER_START" -v e="$MARKER_END" \
        'BEGIN{p=0} $0==s{p=1; next} $0==e{p=0} p{print}' "$README")"
    expected="$(generate_table)"

    if [ "$current" = "$expected" ]; then
        echo "OK — ADR index in $README matches generated content."
        exit 0
    else
        echo "DRIFT — ADR index in $README differs from generated content." >&2
        echo "Run: bin/dev/regen-adr-index.sh --in-place" >&2
        # Show a compact diff so CI logs are actionable.
        diff <(printf '%s\n' "$current") <(printf '%s\n' "$expected") || true
        exit 1
    fi
}

# -----------------------------------------------------------------------------
# --in-place mode: replace the content between markers with the generated
# table in README.md.
# -----------------------------------------------------------------------------
in_place() {
    if ! grep -qF "$MARKER_START" "$README" || ! grep -qF "$MARKER_END" "$README"; then
        echo "ERROR: ADR-INDEX markers missing from $README — add them first." >&2
        exit 2
    fi

    # Use a temp file for the generated table rather than an awk -v variable
    # (awk chokes on multiline -v values on BSD awk / macOS). The awk script
    # uses getline to inject the file content verbatim after the start marker
    # and skips the old content up to the end marker.
    local tmp_table tmp_readme
    tmp_table="$(mktemp)"
    tmp_readme="$(mktemp)"
    generate_table > "$tmp_table"

    awk -v s="$MARKER_START" -v e="$MARKER_END" -v tf="$tmp_table" '
        BEGIN { p=1 }
        $0 == s {
            print
            while ((getline line < tf) > 0) print line
            close(tf)
            p = 0
            next
        }
        $0 == e { p = 1 }
        p { print }
    ' "$README" > "$tmp_readme"
    mv "$tmp_readme" "$README"
    rm -f "$tmp_table"
    echo "Updated $README — flat index regenerated from $(ls "${ADR_DIR}"/[0-9][0-9][0-9][0-9]-*.md | wc -l | tr -d ' ') ADR files."
}

case "$mode" in
    print)    generate_table ;;
    in-place) in_place ;;
    check)    check_drift ;;
esac
