#!/usr/bin/env bash
set -e

case "$1" in

  db)
    echo "Starting PostgreSQL..."
    docker compose up -d db
    ;;

  obs)
    echo "Starting observability stack..."
    docker compose -f docker-compose.observability.yml up -d
    ;;

  all)
    echo "Starting everything..."
    docker compose up -d db
    docker compose -f docker-compose.observability.yml up -d
    mvn spring-boot:run
    ;;

  *)
    echo "Usage:"
    echo "  ./run.sh db     # start database"
    echo "  ./run.sh obs    # start observability"
    echo "  ./run.sh all    # start everything"
    echo "  mvn spring-boot:run # start app"
    ;;
esac