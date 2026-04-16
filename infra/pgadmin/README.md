# `infra/pgadmin/` — pgAdmin pre-configuration for local development

This directory holds two small files mounted into the `pgadmin` container by
`docker-compose.yml`. Together they make the "Connect to the local Postgres"
step a zero-click experience: as soon as you open <http://localhost:5050>, the
`customer-service (local)` server is already visible in the tree, and clicking
it connects immediately without asking for a password.

## Why pre-configure at all?

`dpage/pgadmin4` starts empty — every developer would otherwise have to click
through the "Register Server" wizard, pick the host (`db`), the port, the
database name, the user, and paste the password. That's 6 fields of
boilerplate on every fresh checkout or after `docker compose down -v`.

Pre-configuration shaves this to zero clicks and eliminates the inevitable
"what's the DB host again?" Slack question. Crucially, the files are
**committed to Git** so the experience is consistent across all developers.

These credentials are **demo-grade and intentionally hard-coded** — the
password is `demo`, pgAdmin itself runs without a master password and in
desktop mode (no login screen). This is fine because:

- `docker-compose.yml` only exposes pgAdmin on `127.0.0.1:5050`
- the postgres container is also only reachable from inside the compose network
- the same credentials are used everywhere in the stack for local dev

**None of this is used in production.** Cloud SQL uses IAM auth via the
Cloud SQL Auth Proxy (see `deploy/kubernetes/gke/cloud-sql-proxy.yaml`).

## Files in this directory

| File          | Role                                                                                                                                                         | Mounted to                    |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------- |
| `servers.json` | Pre-registers the `customer-service (local)` Postgres server in pgAdmin's left tree so it's visible immediately. Points at host `db` (the compose service name), port 5432, DB `customer-service`, user `demo`, and the password file below. | `/pgadmin4/servers.json`      |
| `pgpassfile`  | Supplies the password so pgAdmin connects without prompting. Format: `hostname:port:database:user:password`. Referenced by `servers.json` via `PassFile`.    | `/pgadmin4/pgpassfile`        |
| `README.md`   | This file.                                                                                                                                                   | (not mounted)                 |

## When to edit these files

- **New database added to the local stack?** Add another entry to `servers.json`
  and extend `pgpassfile` with a new line.
- **Changed `DB_USER` / `DB_PASSWORD` in `.env`?** Keep these files in sync
  — otherwise pgAdmin will fail silently with "authentication failed".
- **Connection name annoying?** Edit the `"Name"` key in `servers.json`;
  it only affects the display label in the pgAdmin tree.

## Mount wiring (from `docker-compose.yml`)

```yaml
pgadmin:
  volumes:
    - ./infra/pgadmin/servers.json:/pgadmin4/servers.json:ro
    - ./infra/pgadmin/pgpassfile:/pgadmin4/pgpassfile:ro
```

Both files are mounted read-only — pgAdmin will NOT overwrite them during its
startup dance even if it tries.
