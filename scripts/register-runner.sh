#!/usr/bin/env bash
# =============================================================================
# register-runner.sh — register the local GitLab Runner with gitlab.com
#
# Prerequisites:
#   1. Runner container must be running:
#      docker compose -f docker-compose.runner.yml up -d
#
#   2. Get a runner token from gitlab.com:
#      Project → Settings → CI/CD → Runners → New project runner
#      ✓ "Run untagged jobs"  ✓ "Lock to current project"
#      Click "Create runner" → copy the token (glrt-xxxxxxxxxxxx)
#
# Usage:
#   ./scripts/register-runner.sh <TOKEN>
#   ./scripts/register-runner.sh glrt-xxxxxxxxxxxx
# =============================================================================

set -euo pipefail

BLUE='\033[0;34m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${BLUE}▶  $*${NC}"; }
ok()   { echo -e "${GREEN}✓  $*${NC}"; }
die()  { echo -e "${RED}✗  $*${NC}" >&2; exit 1; }

TOKEN="${1:-}"
[[ -z "$TOKEN" ]] && die "Usage: $0 <RUNNER_TOKEN>\n  Get it from: gitlab.com → Project → Settings → CI/CD → Runners → New project runner"

# Check the runner container is up
docker ps --filter "name=gitlab-runner" --filter "status=running" --format "{{.Names}}" \
  | grep -q "gitlab-runner" \
  || die "Runner container is not running. Start it first:\n  docker compose -f docker-compose.runner.yml up -d"

log "Registering runner with gitlab.com..."

docker exec -it gitlab-runner gitlab-runner register \
  --non-interactive \
  --url "https://gitlab.com" \
  --token "$TOKEN" \
  --executor "docker" \
  --docker-image "maven:3.9.14-eclipse-temurin-25-noble" \
  --description "local-runner-$(hostname)" \
  --docker-volumes "/var/run/docker.sock:/var/run/docker.sock" \
  --docker-volumes "/root/.m2:/root/.m2:rw" \
  --docker-pull-policy "if-not-present" \
  --run-untagged="true" \
  --locked="false"

# ── Post-registration: tune concurrency ──────────────────────────────────────
# GitLab Runner defaults to 1 concurrent job. Bump to match your CPU cores.
CORES=$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
CONCURRENCY=$(( CORES > 2 ? CORES - 1 : 1 ))   # leave 1 core for the OS

docker exec gitlab-runner sed -i \
  "s/^concurrent = .*/concurrent = ${CONCURRENCY}/" \
  /etc/gitlab-runner/config.toml

ok "Runner registered — ${CONCURRENCY} concurrent jobs (${CORES} cores detected)"

# ── Show config summary ───────────────────────────────────────────────────────
echo ""
echo -e "  ${BLUE}Runner config:${NC}"
docker exec gitlab-runner cat /etc/gitlab-runner/config.toml \
  | grep -E "concurrent|name|url|executor|image" | sed 's/^/  /'

echo ""
echo -e "  ${GREEN}Next push will trigger jobs on this machine.${NC}"
echo -e "  Monitor:  ${BLUE}docker logs -f gitlab-runner${NC}"
echo ""
echo -e "  To unregister:  ${YELLOW}docker exec gitlab-runner gitlab-runner unregister --all-runners${NC}"
