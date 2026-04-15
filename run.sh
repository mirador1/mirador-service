#!/usr/bin/env bash
# =============================================================================
# run.sh — unified entry point for local development and CI tasks
#
# Usage:
#   ./run.sh <command>
#
# Infrastructure commands (Docker Compose):
#   db        start PostgreSQL
#   kafka     start Kafka
#   obs       start observability stack (Prometheus, Grafana/LGTM, Zipkin)
#   app       start Spring application (local Maven)
#   all       start everything (db + kafka + obs + app)
#   restart   stop + clean restart of all containers, then start app
#   stop      stop app + all containers (infra, obs, GitLab)
#   nuke      full cleanup — containers, volumes, build artifacts
#   status    check status of all services and print URLs
#   simulate  run HTTP traffic simulation (default: 60 iterations, 2s pause)
#
# GitLab CI/CD commands:
#   gitlab         start local GitLab CE (http://localhost:9081)
#   gitlab-stop    stop local GitLab CE + runner
#   runner         start GitLab Runner (docker-compose.runner.yml)
#   runner-stop    stop GitLab Runner
#   register       register runner against local GitLab (./scripts/register-runner.sh local <TOKEN>)
#   register-cloud register runner against gitlab.com  (./scripts/register-runner.sh cloud <TOKEN>)
#
# Quality commands (mirror CI pipeline — no Docker needed except for 'integration'):
#   lint      Dockerfile linting with hadolint
#   test      unit tests only (fast, no Docker)
#   check     alias for test
#   integration  integration tests + SpotBugs + JaCoCo (needs Docker for Testcontainers)
#   verify    full pipeline: lint + test + integration
#   ci        alias for verify
#   package   build the fat JAR (run verify first)
#   docker    build local JVM Docker image
#   clean     wipe target/
#   site      generate Maven quality reports and serve them at http://localhost:8084
#             Runs: mvn verify && mvn site → starts the nginx maven-site container
#             The site is regenerated daily in CI (REPORT_PIPELINE=true schedule)
#
# Setup:
#   ./run.sh install-tools    install hadolint + lefthook via Homebrew
# =============================================================================
set -e

MVNW="./mvnw"
MAVEN="$MVNW --batch-mode --errors --no-transfer-progress"
IMAGE="mirador:local"

# Ensure Docker is running for commands that need it
ensure_docker() {
  if docker info >/dev/null 2>&1; then
    return
  fi
  echo "Docker is not running — attempting to start..."
  case "$(uname -s)" in
    Darwin)
      open -a Docker
      ;;
    Linux)
      if command -v systemctl &>/dev/null; then
        sudo systemctl start docker
      else
        echo "ERROR: Cannot start Docker automatically. Please start it manually."
        exit 1
      fi
      ;;
    *)
      echo "ERROR: Unsupported OS. Please start Docker manually."
      exit 1
      ;;
  esac
  echo -n "Waiting for Docker"
  while ! docker info >/dev/null 2>&1; do
    echo -n "."
    sleep 2
  done
  echo " ready!"
}

case "$1" in

  # ---------------------------------------------------------------------------
  # Infrastructure
  # ---------------------------------------------------------------------------

  db)
    ensure_docker
    echo "Starting PostgreSQL..."
    docker compose up -d db
    ;;

  kafka)
    ensure_docker
    echo "Starting Kafka..."
    docker compose up -d kafka
    ;;

  obs)
    ensure_docker
    echo "Starting observability stack..."
    docker compose -f docker-compose.observability.yml up -d
    ;;

  gitlab)
    ensure_docker
    echo "Starting local GitLab CE (http://localhost:9081)..."
    echo "First-run initialisation takes ~2-3 minutes."
    docker compose -f docker-compose.gitlab.yml up -d
    echo ""
    echo "  Watch logs:  docker logs -f gitlab"
    echo "  Ready when:  curl -s http://localhost:9081/-/health"
    ;;

  gitlab-stop)
    echo "Stopping local GitLab CE + runner..."
    docker compose -f docker-compose.gitlab.yml down
    docker compose -f docker-compose.runner.yml down
    ;;

  runner)
    ensure_docker
    echo "Starting GitLab Runner..."
    docker compose -f docker-compose.runner.yml up -d
    echo ""
    echo "  Runner is up. Register it with:"
    echo "    ./run.sh register        — against local GitLab (localhost:9081)"
    echo "    ./run.sh register-cloud  — against gitlab.com"
    ;;

  runner-stop)
    echo "Stopping GitLab Runner..."
    docker compose -f docker-compose.runner.yml down
    ;;

  register)
    TOKEN="${2:-}"
    if [ -z "$TOKEN" ]; then
      echo "Usage: ./run.sh register <TOKEN>"
      echo ""
      echo "  Get the token from: http://localhost:9081 → Project → Settings → CI/CD → Runners"
      exit 1
    fi
    ./scripts/register-runner.sh local "$TOKEN"
    ;;

  register-cloud)
    TOKEN="${2:-}"
    if [ -z "$TOKEN" ]; then
      echo "Usage: ./run.sh register-cloud <TOKEN>"
      echo ""
      echo "  Get the token from: gitlab.com → Project → Settings → CI/CD → Runners"
      exit 1
    fi
    ./scripts/register-runner.sh cloud "$TOKEN"
    ;;

  app)
    echo "Starting Spring app (local)..."
    $MVNW spring-boot:run
    ;;


  all)
    ensure_docker
    echo "Starting everything..."
    # Start infra services only (not the app container — we run locally via Maven)
    docker compose up -d db kafka redis ollama keycloak pgadmin kafka-ui redisinsight
    # Start observability stack
    docker compose -f docker-compose.observability.yml up -d
    # Wait for DB to be healthy before starting the app
    echo -n "Waiting for PostgreSQL"
    until docker inspect -f '{{.State.Health.Status}}' postgres-demo 2>/dev/null | grep -q healthy; do
      echo -n "."
      sleep 2
    done
    echo " ready!"
    $MVNW spring-boot:run
    ;;

  simulate)
    echo "Starting traffic simulation..."
    ./infra/simulate-traffic.sh "${2:-60}" "${3:-2}"
    ;;

  restart)
    ensure_docker
    echo "Restarting everything (clean)..."
    # Kill the running Spring app (target Java process only, not Docker)
    pgrep -f 'MiradorApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    # Stop all containers (both compose files)
    docker compose -f docker-compose.observability.yml down
    docker compose down
    # Start infra (not the app container — we run locally via Maven)
    docker compose up -d db kafka redis ollama keycloak pgadmin kafka-ui redisinsight
    # Start observability stack
    docker compose -f docker-compose.observability.yml up -d
    # Wait for DB to be healthy before starting the app
    echo -n "Waiting for PostgreSQL"
    until docker inspect -f '{{.State.Health.Status}}' postgres-demo 2>/dev/null | grep -q healthy; do
      echo -n "."
      sleep 2
    done
    echo " ready!"
    echo "Infrastructure ready. Starting app..."
    $MVNW spring-boot:run
    ;;

  nuke)
    ensure_docker
    echo "Full cleanup — removing containers, volumes, and build artifacts..."
    pgrep -f 'MiradorApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    docker compose down -v
    docker compose -f docker-compose.observability.yml down -v
    docker compose -f docker-compose.runner.yml down -v 2>/dev/null || true
    # GitLab volumes are large — only wipe if explicitly requested
    if [ "${2:-}" = "--with-gitlab" ]; then
      echo "Wiping GitLab CE volumes (this deletes all repos and CI history)..."
      docker compose -f docker-compose.gitlab.yml down -v
    else
      docker compose -f docker-compose.gitlab.yml down 2>/dev/null || true
      echo "  (GitLab volumes preserved — use './run.sh nuke --with-gitlab' to also wipe them)"
    fi
    $MAVEN clean
    echo "Done. Run './run.sh all' to start from scratch."
    ;;

  stop)
    echo "Stopping everything..."
    pgrep -f 'MiradorApplication' | xargs kill 2>/dev/null || true
    pgrep -f 'spring-boot:run' | xargs kill 2>/dev/null || true
    docker compose down
    docker compose -f docker-compose.observability.yml down
    docker compose -f docker-compose.runner.yml down 2>/dev/null || true
    docker compose -f docker-compose.gitlab.yml down 2>/dev/null || true
    ;;

  # ---------------------------------------------------------------------------
  # Quality / CI
  # ---------------------------------------------------------------------------

  lint)
    echo "Running Dockerfile linting..."
    if command -v hadolint &>/dev/null; then
      hadolint Dockerfile
    else
      echo "hadolint not found — running via Docker (install with: ./run.sh install-tools)"
      docker run --rm -i hadolint/hadolint < Dockerfile
    fi
    ;;

  test|check)
    echo "Running unit tests (fast, no Docker)..."
    $MAVEN test
    ;;

  integration)
    echo "Running integration tests + SpotBugs + JaCoCo (needs Docker)..."
    $MAVEN verify -Dsurefire.skip=true
    ;;

  verify|ci)
    echo "Running full pipeline: lint + unit + integration..."
    "$0" lint
    "$0" test
    "$0" integration
    ;;

  package)
    echo "Building fat JAR (skipping tests — run verify first)..."
    $MAVEN -DskipTests package
    ;;

  docker)
    echo "Building local JVM Docker image..."
    docker build -t "$IMAGE" .
    echo ""
    echo "Image built: $IMAGE"
    echo "Run with: docker compose up -d"
    ;;

  security-check)
    echo "Running OWASP Dependency-Check (CVE scan)..."
    $MAVEN dependency-check:check
    echo "Report: target/dependency-check-report.html"
    ;;

  site)
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
    docker compose up -d maven-site
    echo ""
    echo "  Maven Site  http://localhost:8084"
    echo "  Reports:    Surefire · Failsafe · JaCoCo · SpotBugs · Mutation Testing · Javadoc"
    echo ""
    echo "  To stop:    docker compose stop maven-site"
    echo "  To rebuild: ./run.sh site"
    ;;

  sonar)
    # Run SonarQube analysis against the local Docker SonarQube instance (port 9000).
    # Prerequisites:
    #   1. docker compose up -d sonarqube  (wait ~2 min for first startup)
    #   2. Visit http://localhost:9000, log in (admin/admin), change password
    #   3. Create a project manually OR let sonar:sonar auto-create it
    #   4. Generate a token: My Account → Security → Generate Token
    #   5. Set SONAR_TOKEN in .env
    #
    # Runs mvn verify first to produce JaCoCo XML (required by Sonar for coverage).
    # Skip ITs to keep it fast; Sonar reads unit-test coverage only.
    if [ -z "$SONAR_TOKEN" ]; then
      echo "Error: SONAR_TOKEN is not set in .env."
      echo "Generate one at http://localhost:9000 → My Account → Security → Generate Token"
      exit 1
    fi
    echo "Running tests + SonarQube analysis..."
    $MAVEN verify -DskipITs -q
    $MAVEN sonar:sonar \
      -Dsonar.token="$SONAR_TOKEN" \
      -Dsonar.host.url=http://localhost:9000
    echo ""
    echo "  SonarQube report: http://localhost:9000/dashboard?id=mirador"
    ;;

  clean)
    echo "Cleaning build artifacts..."
    $MAVEN clean
    ;;

  install-tools)
    echo "Installing hadolint + lefthook via Homebrew..."
    brew install hadolint lefthook
    lefthook install
    echo ""
    echo "Tools installed. Git pre-push hook is now active."
    echo "Every 'git push' will automatically run './run.sh check' (unit tests)."
    ;;

  status)
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
    for svc in pgadmin kafka-ui redisinsight maven-site; do
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
    for svc in customerservice-prometheus customerservice-lgtm customerservice-zipkin; do
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
    GITLAB_STATUS=$(docker inspect -f '{{.State.Status}}' "gitlab" 2>/dev/null || echo "missing")
    RUNNER_STATUS=$(docker inspect -f '{{.State.Status}}' "gitlab-runner" 2>/dev/null || echo "missing")
    if [ "$GITLAB_STATUS" = "running" ]; then
      GITLAB_LABEL="✅ $GITLAB_STATUS"
    else
      GITLAB_LABEL="⬚  $GITLAB_STATUS"
    fi
    if [ "$RUNNER_STATUS" = "running" ]; then
      RUNNER_LABEL="✅ $RUNNER_STATUS"
    else
      RUNNER_LABEL="⬚  $RUNNER_STATUS"
    fi
    printf "  %-22s%s\n" "gitlab (local)" "$GITLAB_LABEL"
    printf "  %-22s%s\n" "gitlab-runner" "$RUNNER_LABEL"

    echo ""
    echo "  ── URLs ─────────────────────────────────────────────────────"
    echo "  App           http://localhost:8080"
    echo "  Swagger       http://localhost:8080/swagger-ui.html"
    echo "  pgAdmin       http://localhost:5050"
    echo "  Kafka UI      http://localhost:9080"
    echo "  RedisInsight  http://localhost:5540"
    echo "  Keycloak      http://localhost:9090"
    echo "  Grafana       http://localhost:3000  (incl. Pyroscope Explore Profiles)"
    echo "  Zipkin        http://localhost:9411"
    echo "  Prometheus    http://localhost:9091"
    echo "  Maven Site    http://localhost:8084  (run './run.sh site' to generate)"
    echo "  Compodoc      http://localhost:8085  (run 'cd ../mirador-ui && npm run compodoc' to generate)"
    echo "  SonarQube     http://localhost:9000  (run './run.sh sonar' after setting SONAR_TOKEN in .env)"
    echo "  GitLab (local) http://localhost:9081"
    echo ""
    ;;

  *)
    echo ""
    echo "Usage: ./run.sh <command>"
    echo ""
    echo "Infrastructure:"
    echo "  db            start PostgreSQL"
    echo "  kafka         start Kafka"
    echo "  obs           start observability stack"
    echo "  app           start Spring app (local)"
      echo "  all           start everything (infra + obs + app)"
    echo "  restart       stop + restart everything (keeps data)"
    echo "  simulate      run traffic simulation (default: 60 iterations, 2s pause)"
    echo "  stop          stop app + all containers"
    echo "  nuke          full cleanup — containers, volumes, build artifacts"
    echo "  status        check status of all services"
    echo ""
    echo "GitLab CI/CD:"
    echo "  gitlab         start local GitLab CE (http://localhost:9081)"
    echo "  gitlab-stop    stop local GitLab CE + runner"
    echo "  runner         start GitLab Runner"
    echo "  runner-stop    stop GitLab Runner"
    echo "  register <TOKEN>        register runner against local GitLab"
    echo "  register-cloud <TOKEN>  register runner against gitlab.com"
    echo ""
    echo "Quality / CI:"
    echo "  check         unit tests only — fast, no Docker required"
    echo "  test          alias for check"
    echo "  verify        full pipeline: lint + unit + integration"
    echo "  ci            alias for verify"
    echo "  lint          Dockerfile linting (hadolint)"
    echo "  integration   IT + SpotBugs + JaCoCo (needs Docker)"
    echo "  package       build fat JAR — skips tests (run verify first)"
    echo "  docker        build local JVM Docker image tagged '$IMAGE'"
    echo "  security-check OWASP Dependency-Check (CVE scan)"
    echo "  site          generate Maven reports + serve at http://localhost:8084"
    echo "  clean         remove target/"
    echo "  install-tools install hadolint + lefthook via Homebrew"
    echo ""
    ;;
esac
