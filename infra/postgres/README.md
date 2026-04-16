# `infra/postgres/` — PostgreSQL init scripts for local development

This directory holds **one-shot SQL scripts** that run the very first time the
local Postgres container starts. They provision databases and users that are
required by other services in the compose stack (SonarQube today, potentially
others later) but live **alongside** the main application database on the same
Postgres instance.

## Why a single shared Postgres for everything?

For local development, running three or four separate Postgres containers
(one for the app, one for SonarQube, one for tests, etc.) would:

- waste memory (each Postgres reserves ~200 MB of shared buffers)
- complicate port management (each needs its own host port)
- make backups, cleanup (`docker compose down -v`), and inspection harder

Instead, the stack runs **one Postgres 17 container** (`db` service in
`docker-compose.yml`) and every tool that needs a database gets its own
logical database and user on that shared instance. The scripts here are what
keep that setup one-shot:

> When `docker compose up -d db` runs on a **fresh volume**, Postgres
> auto-executes every `*.sql` file in `/docker-entrypoint-initdb.d/` in
> alphabetical order. We mount this directory there, so the scripts run
> exactly once — right after the main database is created.

**Production does not use this.** On GKE, SonarCloud (SaaS) replaces the local
SonarQube, and the application uses Cloud SQL via the Cloud SQL Auth Proxy.
These init scripts are a local-dev convenience only.

### What "fresh volume" means

The scripts **do NOT** run if the Postgres data volume already exists. This
is by design — running them twice would fail with "database already exists".
If you need to re-run them after the first start:

```bash
# Option A: destroy the volume and re-init everything
docker compose down -v
docker compose up -d db

# Option B: run the SQL manually on the existing instance
docker exec -i postgres-demo psql -U demo -d customer-service < infra/postgres/init-sonar.sql
```

## Files in this directory

| File              | Role                                                                                                                                                                                                                                                   | Runs when                           |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| `init-sonar.sql`  | Creates the `sonar` database (UTF8-encoded) and the `sonar` role owning it, used by the optional local SonarQube container. Grants full privileges so SonarQube can manage its own schema on first boot. Safe to remove if you don't run SonarQube locally. | First container start on fresh vol |
| `README.md`       | This file.                                                                                                                                                                                                                                              | (not executed)                      |

## Mount wiring (from `docker-compose.yml`)

```yaml
db:
  volumes:
    - ./infra/postgres/init-sonar.sql:/docker-entrypoint-initdb.d/init-sonar.sql:ro
```

Read-only mount — Postgres will never rewrite these files. If you add a new
init script, mount it similarly and keep alphabetical order in mind: files
run in the order `ls -1` returns.

## Adding a new init script

1. Create `init-<thing>.sql` in this directory with idempotent-where-possible
   SQL (use `CREATE USER IF NOT EXISTS` / `CREATE DATABASE IF NOT EXISTS` when
   the tooling supports it).
2. Add a read-only mount to `docker-compose.yml` under the `db` service.
3. Document its purpose in the table above.
4. If you want it to also apply to existing volumes, run it manually via
   `psql` (see Option B above).

## Conventions

- Filename prefix `init-<tool>.sql` keeps the directory tidy as it grows.
- Scripts should NOT assume a specific user is connected — explicitly set
  ownership via `OWNER sonar` / `OWNER keycloak` / etc.
- Avoid `DROP` statements. This directory is for provisioning; teardown
  happens via `docker compose down -v`.
