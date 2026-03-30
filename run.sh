#!/bin/bash

set -e

# =========================
# CONFIG (modifiable)
# =========================
APP_NAME=${APP_NAME:-spring-app}
PORT=${PORT:-8080}
IMAGE_TAG=${IMAGE_TAG:-latest}

DB_URL=${DB_URL:-jdbc:postgresql://host.docker.internal:5432/demo}
DB_USER=${DB_USER:-demo}
DB_PASSWORD=${DB_PASSWORD:-demo}

JAVA_OPTS=${JAVA_OPTS:--Xms256m -Xmx512m}

# =========================
# BUILD
# =========================
echo ">>> Build Maven"
mvn clean package -DskipTests

echo ">>> Build Docker image"
docker build -t ${APP_NAME}:${IMAGE_TAG} .

# =========================
# RUN
# =========================
echo ">>> Run container"

docker run \
  -p ${PORT}:8080 \
  -e SPRING_DATASOURCE_URL=${DB_URL} \
  -e SPRING_DATASOURCE_USERNAME=${DB_USER} \
  -e SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD} \
  -e JAVA_OPTS="${JAVA_OPTS}" \
  ${APP_NAME}:${IMAGE_TAG}