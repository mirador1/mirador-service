# `docs/` — Human-targeted documentation

This folder is the **reference material** for the project: architecture
overview, API contracts, security model, and observability strategy. Each
topic lives in its own file so the root `README.md` can stay lean and the
deep-dives can grow independently.

## Topic documents

| File                   | Audience                               | Contents                                                                                                            |
| ---------------------- | -------------------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| `architecture.md`      | New contributors, reviewers            | Top-down walk-through: modules, component diagram, data-flow for the main use cases.                                |
| `api.md`               | API consumers (internal + external)    | Resource-oriented API reference with curl examples. Partial — live source of truth is the Swagger UI at `/swagger`. |
| `api-contract.md`      | API consumers, clients                  | Versioning policy (`X-API-Version` header vs. URL path), deprecation rules, BC guarantees.                          |
| `security.md`          | Security reviewers, auditors           | Threat model, auth flows (JWT + OAuth2 + API key), CVE handling process, dependency-check workflow.                 |
| `observability.md`     | SREs, ops team                         | Metrics taxonomy, trace sampling strategy, log levels, dashboard inventory, alert runbooks.                         |

## Auxiliary assets

| File / folder         | Role                                                                                                                             |
| --------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `banner.svg`          | Project banner displayed at the top of the root `README.md`.                                                                    |
| `auth0-action-roles.js` | Reference implementation of the Auth0 Action that injects realm roles into issued tokens (used in `JwtAuthenticationFilter`).  |
| `outputs/`            | Sample API responses and Prometheus scrape dumps used as reference data in docs, tests, and live demo comparisons.              |
| `screenshots/`        | Screenshots embedded in the topic documents (Grafana dashboards, Prometheus UI, etc.).                                          |

## What belongs here vs. elsewhere

| Content                                    | Lives in                      |
| ------------------------------------------ | ----------------------------- |
| Long-form prose for humans                 | `docs/` ← this directory      |
| Auto-generated API spec (OpenAPI)          | Served at `/v3/api-docs`      |
| Auto-generated Javadoc                     | `target/site/apidocs/` (via `mvn site`) |
| Auto-generated Angular API reference       | `../mirador-ui/docs/compodoc/` (via `npm run compodoc`) |
| Per-directory orientation                  | `README.md` in each directory |
| Session/task tracking for Claude           | `/TASKS.md`, `/CLAUDE.md`     |

## Contributing

- Keep each topic file **self-contained** — cross-link to others rather
  than duplicating content.
- Update `architecture.md` when adding a new module or changing a major
  data-flow path.
- Screenshots go into `screenshots/`, referenced with relative paths
  (`![](screenshots/foo.png)`).
- If a section outgrows its file, split by audience (dev, ops, sec) rather
  than by feature.
