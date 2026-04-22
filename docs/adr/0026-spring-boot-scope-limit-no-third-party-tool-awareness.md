# ADR-0026 — Spring Boot scope = app domain + self-admin only; no awareness of third-party tools

- **Status**: Accepted
- **Date**: 2026-04-18
- **Partial revert of**: ADR-0024 (the three BFF endpoints it introduced are removed)
- **Related**: ADR-0025 (no public prod ingress), ADR-0016 (ESO)

## Context

Over the last few sessions the Spring Boot backend grew a small family
of BFF endpoints that proxied queries to **third-party tools** the app
itself did not need to call:

- `/obs/loki/query_range` — proxied LogQL queries to Loki
- `/obs/tempo/traces/{id}` — proxied TraceQL lookups to Tempo
- `/features` — polled Unleash's client API, cached the flag map

Each one was individually defensible (single auth surface for the UI,
no CORS, topology hidden). Collectively they made Spring Boot **aware of
the observability stack and the feature-flag server** that sit
orthogonally to the app — Spring Boot now had to know `LOKI_URL`,
`TEMPO_URL`, `UNLEASH_URL`, parse each tool's JSON shape, and re-emit
it. That is someone else's job.

The same pressure was about to push a `/admin/sql` endpoint into the
backend when the Database page lost its pgweb proxy with MR 77. Stopped
before merging.

## Decision

**Spring Boot scope is strictly limited to:**

- The **app domain** — customers, bio generation, resilience, idempotency,
  rate limiting — and the HTTP/Kafka API that exposes it.
- **Self-admin surface** — Actuator (health, info, metrics, prometheus,
  loggers, maintenance), custom endpoints like `/actuator/quality`.
- **Direct functional dependencies via their native protocol** —
  PostgreSQL via JDBC, Kafka via the producer/consumer API, Redis via
  Lettuce, Keycloak via OIDC, Ollama via Spring AI.
- **Observability emission via push** — OpenTelemetry OTLP outbound to
  the configured Collector endpoint.

**Spring Boot is explicitly NOT aware of:**

- The **UIs / admin tools** of its dependencies (pgweb, CloudBeaver,
  Kafka UI, RedisInsight). Those are separate products with their own
  auth, their own UI, their own lifecycle.
- The **consultation side of observability** — Loki query API, Tempo
  query API, Grafana API, Mimir read path. The backend pushes telemetry;
  it does not pull telemetry back for a UI to display.
- **Orthogonal infrastructure tools** — Argo CD, Unleash's admin API,
  Chaos Mesh dashboard, cert-manager API. If the app functionally
  depends on one of these (e.g. Unleash as a functional feature flag
  for a code path), the official SDK speaks the native protocol; that
  is not a violation. Proxying the admin UI of these tools **is**.

## Corollary — what each tool's UI reaches

| UI need                        | Previous path                        | New path                                                                                 |
|--------------------------------|--------------------------------------|------------------------------------------------------------------------------------------|
| Postgres diagnostic SQL        | UI → Spring Boot? → Postgres         | UI → pgweb (HTTP→PG bridge, compose-only container). Prod = local CloudBeaver + port-forward Postgres |
| LogQL query                    | UI → Spring Boot `/obs/loki/*` → Loki| UI → Grafana datasource proxy `/api/datasources/proxy/uid/loki`                          |
| TraceQL query                  | UI → Spring Boot `/obs/tempo/*` → Tempo | UI → Grafana datasource proxy `/api/datasources/proxy/uid/tempo`                         |
| Feature flag state             | UI → Spring Boot `/features` → Unleash | UI → `unleash-proxy` sidecar (official front-end proxy, compose + cluster)               |
| Grafana dashboards             | iframe/link already direct           | unchanged                                                                                |
| Kafka topics / consumer groups | Kafka UI already direct              | unchanged                                                                                |
| Redis keys                     | RedisInsight already direct          | unchanged                                                                                |

## Consequences

### Positive

- **Clear separation of concerns.** The backend does not rot when
  Grafana, Loki, Tempo, Unleash change their API shapes.
- **No SQL-over-HTTP surface inside Spring Boot.** The hardest thing
  to get right at scale (query whitelisting, prepared-statement
  translation, side-effect prevention) stays in a purpose-built tool
  (pgweb) with a narrow blast radius and its own `--readonly` gate.
- **Smaller backend** — ~300 lines removed (two controllers + their
  config, tests, ADR-0024 companion content).
- **Easier audit story.** A CKS / security review can state: "the
  backend exposes customers + self-admin + actuator — nothing else".

### Negative

- **More moving parts at the edge.** pgweb in compose, unleash-proxy
  in compose + cluster, Grafana datasource proxy relied on for
  observability queries. Three tools to operate instead of three
  endpoints to write.
- **Per-tool auth matters more.** Each tool's security posture needs to
  stand on its own: pgweb's `--readonly` + CORS + network isolation,
  unleash-proxy's client token, Grafana's anonymous-viewer role in
  compose.
- **UI becomes env-sensitive.** Some features (SQL Explorer) only work
  in compose mode; `EnvService` gates them with `@if (env.pgwebUrl())`.
  Prod tunnel mode gets a "use CloudBeaver locally" hint. This is a
  better reflection of reality than pretending all features work
  uniformly.

## Alternatives considered

| Option                                           | Why rejected                                                                                                                                       |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Keep the BFFs, treat ADR-0024 as canon           | Violates the scope principle above. Adds recurring maintenance as upstream APIs drift.                                                             |
| BFF SQL endpoint with a strict whitelist         | SQL whitelists are a snake pit (CTE hiding writes, `pg_read_file` via functions, FOR UPDATE in SELECT). Not a battle worth fighting in a portfolio repo. |
| Typed per-diagnostic endpoints (`/admin/db/bloat`, …) | 40+ endpoints to write and maintain. Duplicates what Grafana + pgweb already do.                                                                   |
| Grafana as the sole UI (drop the custom Angular UI) | Defeats the point of the portfolio demo — it exists to showcase a custom UI that consumes observability and feature flags.                         |

## Implementation plan (executed after this ADR lands)

1. **pgweb back in compose**, with three profiles / ports (kind = +10000, prod = +20000):
   - `pgweb-local`: port 8081 → `db:5432` (compose Postgres)
   - `pgweb-kind`  (profile `kind-tunnel`): port 8082 → `host.docker.internal:15432` (kind tunnel)
   - `pgweb-prod`  (profile `prod-tunnel`): port 8083 → `host.docker.internal:25432` (GKE tunnel)
   - Helpers: `bin/cluster/pgweb/kind-up.sh`, `bin/cluster/pgweb/prod-up.sh` (the latter
     pulls the DB password from GSM via ESO).
2. **EnvService** exposes `pgwebUrl` — `:8081` on Local, `:8082` on Prod
   tunnel. The Database page gates SQL Explorer + health checks behind
   `@if (env.pgwebUrl())`.
3. **Remove three BFF endpoints** from the backend:
   - `BffObservabilityController` (obs/loki/\*, obs/tempo/\*)
   - `FeatureFlagController` (/features)
4. **Unleash front-end proxy** (`unleash-proxy` container) in compose +
   cluster for UI flag consumption. The backend's own Unleash access (if
   any) stays via the Java SDK when and if a code path needs a flag.
5. **Observability component** in UI reverts to using Grafana's
   datasource proxy directly.

## Revisit this when

- A cross-cutting concern really does need an app-level proxy (e.g., a
  write-SQL admin console behind a narrow whitelist + audit log). At
  that point ADR-0026 does not forbid it — it forbids the **implicit**
  drift where tools-of-other-concerns leak into the app. Write a new
  ADR for the explicit case.
