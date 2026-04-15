-- Creates the SonarQube database and user on the shared PostgreSQL instance.
-- SonarQube manages its own schema; this script only provisions the database.
-- Executed automatically by postgres on first container start (docker-entrypoint-initdb.d/).
-- Idempotent: IF NOT EXISTS guards prevent errors on subsequent restarts.

CREATE USER sonar WITH PASSWORD 'sonar';
CREATE DATABASE sonar OWNER sonar ENCODING 'UTF8';
GRANT ALL PRIVILEGES ON DATABASE sonar TO sonar;
