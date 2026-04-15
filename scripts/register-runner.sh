#!/usr/bin/env bash
# =============================================================================
# register-runner.sh — register the local GitLab Runner against gitlab.com
#
# Prerequisites:
#   1. Runner container must be running:
#      ./run.sh runner
#
#   2. Get a runner token from gitlab.com:
#      Project → Settings → CI/CD → Runners → New project runner
#      ✓ "Run untagged jobs"   Click "Create runner" → copy the token (glrt-xxxx)
#
# Usage:
#   ./scripts/register-runner.sh <TOKEN>
#   ./run.sh register-cloud <TOKEN>          (convenience wrapper)
# =============================================================================

set -euo pipefail

BLUE='\033[0;34m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
log()  { echo -e "${BLUE}▶  $*${NC}"; }
ok()   { echo -e "${GREEN}✓  $*${NC}"; }
die()  { echo -e "${RED}✗  $*${NC}" >&2; exit 1; }

# Support both:
#   ./scripts/register-runner.sh <TOKEN>
#   ./scripts/register-runner.sh cloud <TOKEN>   (legacy, cloud arg is ignored)
if [[ "${1:-}" == "cloud" ]]; then
  TOKEN="${2:-}"
else
  TOKEN="${1:-}"
fi

[[ -z "$TOKEN" ]] && die "Usage: $0 <RUNNER_TOKEN>\n  Get it from: gitlab.com → Project → Settings → CI/CD → Runners → New project runner"

# Check the runner container is up
docker ps --filter "name=gitlab-runner" --filter "status=running" --format "{{.Names}}" \
  | grep -q "gitlab-runner" \
  || die "Runner container is not running. Start it first:\n  ./run.sh runner"

log "Registering runner against gitlab.com..."

docker exec gitlab-runner gitlab-runner register \
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

echo ""
echo -e "  ${BLUE}Runner config:${NC}"
docker exec gitlab-runner cat /etc/gitlab-runner/config.toml \
  | grep -E "concurrent|name|url|executor|image" | sed 's/^/  /'

echo ""
echo -e "  ${GREEN}Next push will trigger jobs on this machine.${NC}"
echo -e "  Monitor:  ${BLUE}docker logs -f gitlab-runner${NC}"
echo ""
echo -e "  To unregister:  ${YELLOW}docker exec gitlab-runner gitlab-runner unregister --all-runners${NC}"
