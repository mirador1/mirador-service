# bin/run/_preamble.sh — shared preamble for every sub-script under bin/run/
# Sourced (not executed) by each case-script. Provides helper functions and
# `set -e` behaviour consistent with the original run.sh.
#
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
#
# BUG FIX 2026-04-22 — removed stray `/usr/bin/env bash` line that was
# originally the run.sh shebang. When this file is SOURCED (every case
# script does `source _preamble.sh`), that line spawned a new `bash`
# interpreter waiting for stdin, silently hanging the caller. The
# extraction script kept the shebang line because the rest of the block
# is comments; standalone `/usr/bin/env bash` (no `#!` marker) is not a
# comment, it's an executable invocation. Removed entirely — a sourced
# file has no shebang; the interpreter is the caller's.

# =============================================================================
# run.sh — unified entry point for local development and CI tasks
#
# Usage:
#   ./run.sh <command>
#
# Infrastructure commands (Docker Compose):
#   db        start PostgreSQL
#   kafka     start Kafka
#   obs       start observability stack (Grafana/LGTM, Mimir, Pyroscope)
#   app       start Spring application (local Maven)
#   all       start everything (db + kafka + obs + app)
#   restart   stop + clean restart of all containers, then start app
#   stop      stop app + all containers (infra, obs)
#   nuke      full cleanup — containers, volumes, build artifacts
#   status    check status of all services and print URLs
#   simulate  run HTTP traffic simulation (default: 60 iterations, 2s pause)
#
# GitLab CI/CD commands (runners execute on this machine, not gitlab.com shared runners):
#   runner         start GitLab Runner (deploy/compose/runner.yml)
#   runner-stop    stop GitLab Runner
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

