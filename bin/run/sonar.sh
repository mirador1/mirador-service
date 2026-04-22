#!/usr/bin/env bash
# bin/run/sonar.sh — implements `./run.sh sonar`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    # Run SonarQube analysis against the local Docker SonarQube instance (port 9000).
    # Prerequisites:
    #   1. docker compose --profile admin up -d sonarqube (wait ~2 min for first startup)
    #   2. ./run.sh sonar-setup           (disables force-auth, one-time)
    #   3. Generate a token at http://localhost:9000/account/security
    #   4. Set SONAR_TOKEN=<token> in .env
    #
    # Runs mvn verify (unit + integration tests) to produce jacoco-merged.xml.
    # Integration tests are NOT skipped — CustomerController, AuthController and
    # messaging classes are only exercised by @SpringBootTest ITests. Skipping them
    # would yield ~32% coverage instead of ~80%.
    if [ -z "$SONAR_TOKEN" ]; then
      echo "Error: SONAR_TOKEN is not set in .env."
      echo "Generate one at http://localhost:9000/account/security"
      exit 1
    fi
    echo "Running tests + integration tests + SonarQube analysis (this takes ~3 min)..."
    $MAVEN verify -q
    $MAVEN sonar:sonar \
      -Dsonar.token="$SONAR_TOKEN" \
      -Dsonar.host.url=http://localhost:9000
    echo ""
    echo "  SonarQube report: http://localhost:9000/projects"
