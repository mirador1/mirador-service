#!/usr/bin/env bash
# bin/run/security-check.sh — implements `./run.sh security-check`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    echo "Running OWASP Dependency-Check (CVE scan)..."
    $MAVEN dependency-check:check
    echo "Report: target/dependency-check-report.html"
