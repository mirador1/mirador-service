#!/usr/bin/env bash
# bin/run/sonar-setup.sh — implements `./run.sh sonar-setup`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    # First-time SonarQube configuration on a fresh volume.
    # Disables force-authentication so the dashboard is accessible without login (local dev only).
    # Run once after `docker compose --profile admin up -d sonarqube` when the volume is new.
    ensure_docker
    echo "Waiting for SonarQube to be ready..."
    until curl -s http://localhost:9000/api/system/status | grep -q '"status":"UP"'; do
      echo -n "."
      sleep 5
    done
    echo " ready!"
    ADMIN_PASS="${1:-admin}"
    curl -s -X POST -u "admin:${ADMIN_PASS}" \
      "http://localhost:9000/api/settings/set" \
      -d "key=sonar.forceAuthentication&value=false" > /dev/null
    echo "  Force authentication disabled — dashboard accessible without login."
    echo "  SonarQube: http://localhost:9000"
    echo ""
    echo "  Next: generate a token at http://localhost:9000/account/security"
    echo "  Then set SONAR_TOKEN=<token> in .env and run: ./run.sh sonar"
