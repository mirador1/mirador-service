#!/usr/bin/env bash
# bin/run/docker.sh — implements `./run.sh docker`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    echo "Building local JVM Docker image..."
    docker build -t "$IMAGE" .
    echo ""
    echo "Image built: $IMAGE"
    echo "Run with: docker compose up -d"
