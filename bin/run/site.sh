#!/usr/bin/env bash
# bin/run/site.sh — implements `./run.sh site`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    # Generate the full Maven quality report site and serve it via nginx on port 8084.
    #
    # Why this exists:
    #   The CI report schedule (REPORT_PIPELINE=true) generates the site daily and pushes
    #   it to the reports/ branch. Locally, use this command to regenerate on demand —
    #   useful when working on test coverage, SpotBugs fixes, or Javadoc improvements.
    #
    # What it generates (target/site/):
    #   Surefire test results · Failsafe integration test results
    #   JaCoCo coverage report · SpotBugs analysis · Javadoc
    #   Mutation testing (PIT) report at target/site/pit-reports/index.html
    #   Project info: dependencies, licenses, team, source xref
    #   Note: OWASP and pitest HTML are copied by the antrun post-site phase.
    #   Without `post-site`, pit-reports/ and dependency-check-report.html won't appear.
    ensure_docker
    echo "Generating Maven quality reports (mvn verify + site)..."
    echo "  Step 1/2: mvn verify  (runs tests + collects JaCoCo/SpotBugs data)"
    $MAVEN verify -q
    echo "  Step 2/2: mvn site post-site (generates HTML + copies OWASP/pitest reports into site/)"
    $MAVEN site post-site -q
    echo ""
    echo "Starting maven-site nginx container..."
    # `maven-site` now lives under the `docs` profile (2026-04-20) — must
    # activate the profile explicitly for `docker compose up -d <svc>` to
    # target a profile-gated service.
    docker compose --profile docs up -d maven-site
    echo ""
    echo "  Maven Site  http://localhost:8084"
    echo "  Reports:    Surefire · Failsafe · JaCoCo · SpotBugs · Mutation Testing · Javadoc"
    echo ""
    echo "  To stop:    docker compose stop maven-site"
    echo "  To rebuild: ./run.sh site"
