#!/usr/bin/env bash
# bin/run/status.sh — implements `./run.sh status`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    echo ""
    echo "╔══════════════════════════════════════════════════════════════╗"
    echo "║                   mirador status                   ║"
    echo "╚══════════════════════════════════════════════════════════════╝"
    echo ""

    # Docker
    if docker info >/dev/null 2>&1; then
      echo "  Docker              ✅ running"
    else
      echo "  Docker              ❌ not running"
    fi

    # Spring Boot app
    if pgrep -f 'MiradorApplication' >/dev/null 2>&1; then
      echo "  Spring Boot app     ✅ running (PID $(pgrep -f 'MiradorApplication' | head -1))"
    else
      echo "  Spring Boot app     ❌ not running"
    fi

    # Health check
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/actuator/health 2>/dev/null || echo "000")
    if [ "$HTTP_CODE" = "200" ]; then
      echo "  Health check        ✅ UP (http://localhost:8080)"
    else
      echo "  Health check        ❌ DOWN (HTTP $HTTP_CODE)"
    fi

    echo ""
    echo "  ── Infrastructure ──────────────────────────────────────────"
    # Check each container
    for svc in postgres-demo kafka-demo redis-demo ollama keycloak; do
      STATUS=$(docker inspect -f '{{.State.Status}}' "$svc" 2>/dev/null || echo "missing")
      HEALTH=$(docker inspect -f '{{.State.Health.Status}}' "$svc" 2>/dev/null || echo "")
      if [ "$STATUS" = "running" ]; then
        LABEL="✅ $STATUS"
        [ -n "$HEALTH" ] && LABEL="$LABEL ($HEALTH)"
      else
        LABEL="❌ $STATUS"
      fi
      printf "  %-22s%s\n" "$svc" "$LABEL"
    done

    echo ""
    echo "  ── Admin tools ─────────────────────────────────────────────"
    for svc in cloudbeaver kafka-ui redisinsight maven-site sonarqube compodoc; do
      STATUS=$(docker inspect -f '{{.State.Status}}' "$svc" 2>/dev/null || echo "missing")
      if [ "$STATUS" = "running" ]; then
        LABEL="✅ $STATUS"
      else
        LABEL="❌ $STATUS"
      fi
      printf "  %-22s%s\n" "$svc" "$LABEL"
    done

    echo ""
    echo "  ── Observability ────────────────────────────────────────────"
    # LGTM all-in-one: Grafana + OTel Collector + Loki + Tempo + Mimir + Pyroscope
    for svc in customerservice-lgtm customerservice-cors-proxy customerservice-docker-proxy; do
      STATUS=$(docker inspect -f '{{.State.Status}}' "$svc" 2>/dev/null || echo "missing")
      if [ "$STATUS" = "running" ]; then
        LABEL="✅ $STATUS"
      else
        LABEL="⬚  $STATUS"
      fi
      SHORT=$(echo "$svc" | sed 's/customerservice-//')
      printf "  %-22s%s\n" "$SHORT" "$LABEL"
    done

    echo ""
    echo "  ── CI/CD ────────────────────────────────────────────────────"
    RUNNER_STATUS=$(docker inspect -f '{{.State.Status}}' "gitlab-runner" 2>/dev/null || echo "missing")
    if [ "$RUNNER_STATUS" = "running" ]; then
      RUNNER_LABEL="✅ $RUNNER_STATUS"
    else
      RUNNER_LABEL="⬚  $RUNNER_STATUS"
    fi
    printf "  %-22s%s\n" "gitlab-runner" "$RUNNER_LABEL"

    echo ""
    echo "  ── URLs ─────────────────────────────────────────────────────"
    echo "  App           http://localhost:8080"
    echo "  Swagger       http://localhost:8080/swagger-ui.html"
    echo "  pgAdmin       http://localhost:5050"
    echo "  Kafka UI      http://localhost:9080"
    echo "  RedisInsight  http://localhost:5540"
    echo "  Keycloak      http://localhost:8888"
    echo "  Grafana       http://localhost:3000  (Traces · Logs · Metrics · Profiles)"
    echo "  Mimir API     http://localhost:9091  (Prometheus-compatible metrics query)"
    echo "  Maven Site    http://localhost:8084  (run './run.sh site' to generate)"
    echo "  Compodoc      http://localhost:8086  (run 'cd ../mirador-ui && npm run compodoc')"
    echo "  SonarQube     http://localhost:9000  (run './run.sh sonar' after setting SONAR_TOKEN in .env)"
    echo ""
