#!/bin/bash
set -euo pipefail

APP_NAME="${APP_NAME:-spring-app}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

echo ">>> Build Maven"
mvn clean package -DskipTests

echo ">>> Build Docker image"
docker build -t "${APP_NAME}:${IMAGE_TAG}" .

echo ">>> Done: ${APP_NAME}:${IMAGE_TAG}"