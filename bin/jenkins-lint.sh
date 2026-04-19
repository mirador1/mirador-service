#!/usr/bin/env bash
# =============================================================================
# bin/jenkins-lint.sh — validate the Jenkinsfile syntax against a real
# Jenkins declarative linter.
#
# Boot a throwaway Jenkins in Docker, POST the Jenkinsfile to the
# `/pipeline-model-converter/validate` endpoint, read the result, tear
# down. ~45 s cold, ~10 s warm if the image is already pulled.
#
# What it catches: wrong `stages{}` nesting, unknown `when` conditions,
# Groovy typos, missing `agent`, illegal directive in environment, etc.
# What it does NOT catch: stage-level shell errors, missing credentials,
# Docker agent image pull failures — those require a full run (see
# `docs/ops/jenkins.md`).
#
# Rationale for this specific approach in ADR-0029.
#
# Usage:
#   bin/jenkins-lint.sh                # lint ./Jenkinsfile
#   bin/jenkins-lint.sh path/to/file  # lint an arbitrary file
# =============================================================================

set -euo pipefail

FILE="${1:-Jenkinsfile}"
IMAGE="${JENKINS_IMAGE:-jenkins/jenkins:lts-jdk25}"
CONTAINER="${JENKINS_LINT_CONTAINER:-mirador-jenkins-lint}"
PORT="${JENKINS_LINT_PORT:-18082}"

if [[ ! -f "$FILE" ]]; then
  echo "❌  File not found: $FILE"
  exit 2
fi

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "🧹  Ensuring no stale $CONTAINER from a previous run…"
cleanup

echo "🐳  Starting throwaway Jenkins ($IMAGE) on :$PORT…"
# -e JAVA_OPTS skips setup wizard — lint API is reachable without login
# when no user has been configured (first-boot state).
docker run -d \
  --name "$CONTAINER" \
  -p "$PORT:8080" \
  -e JAVA_OPTS="-Djenkins.install.runSetupWizard=false" \
  "$IMAGE" >/dev/null

echo "⏳  Waiting for Jenkins to accept connections (up to 90 s)…"
for i in $(seq 1 90); do
  if curl -sSf -o /dev/null "http://localhost:$PORT/login"; then
    echo "   ✓ up after ${i}s"
    break
  fi
  sleep 1
  if [[ "$i" -eq 90 ]]; then
    echo "❌  Jenkins did not respond in 90 s — check $CONTAINER logs."
    docker logs --tail 30 "$CONTAINER"
    exit 3
  fi
done

echo "📤  Linting $FILE…"
# The validate endpoint accepts the Jenkinsfile as a form field.
# Jenkins 2.414+ returns HTTP 200 with the validation text in the body.
response=$(curl -sS -X POST \
  -F "jenkinsfile=<$FILE" \
  "http://localhost:$PORT/pipeline-model-converter/validate")

echo
echo "── Jenkins response ──────────────────────────────────────"
echo "$response"
echo "──────────────────────────────────────────────────────────"
echo

if echo "$response" | grep -q "Jenkinsfile successfully validated"; then
  echo "✅  $FILE is syntactically valid."
  exit 0
else
  echo "❌  $FILE has syntax errors. See the response above."
  exit 1
fi
