#!/bin/bash
set -euo pipefail

MODE="${1:-full}"

case "$MODE" in
  db)
    echo ">>> Starting postgres only"
    docker compose up -d postgres
    ;;
  full)
    echo ">>> Starting full stack"
    docker compose up --build
    ;;
  down)
    echo ">>> Stopping stack"
    docker compose down
    ;;
  *)
    echo "Usage: ./run.sh [db|full|down]"
    exit 1
    ;;
esac