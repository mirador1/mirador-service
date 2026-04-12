#!/usr/bin/env bash
set -e

case "$1" in

  db)
    echo "Starting PostgreSQL..."
    docker compose up -d db
    ;;

  kafka)
    echo "Starting Kafka..."
    docker compose up -d kafka
    ;;

  obs)
    echo "Starting observability stack..."
    docker compose -f docker-compose.observability.yml up -d
    ;;

  app)
    echo "Starting Spring app (local)..."
    mvn spring-boot:run
    ;;

  all)
    echo "Starting everything..."
    docker compose up -d db kafka
    docker compose -f docker-compose.observability.yml up -d
    mvn spring-boot:run
    ;;

  stop)
    echo "Stopping all containers..."
    docker compose down
    docker compose -f docker-compose.observability.yml down
    ;;

  check)
    echo "Running unit tests (fast, no Docker)..."
    make check
    ;;

  ci)
    echo "Running full local CI pipeline (lint + unit + integration)..."
    make verify
    ;;

  *)
    echo "Usage:"
    echo "  ./run.sh db      # start database"
    echo "  ./run.sh kafka   # start Kafka"
    echo "  ./run.sh obs     # start observability"
    echo "  ./run.sh app     # start Spring app (local)"
    echo "  ./run.sh all     # start everything"
    echo "  ./run.sh stop    # stop all containers"
    echo "  ./run.sh check   # unit tests only (fast, no Docker)"
    echo "  ./run.sh ci      # full pipeline: lint + unit + integration"
    ;;
esac