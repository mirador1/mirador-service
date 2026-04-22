# Mirador Service — Documentation

All long-form documentation for the Spring Boot backend. The root
`README.md` links here; everything more detailed than a paragraph lives
under this tree.

## Layout

```
docs/
├── README.md               ← you are here (index)
├── adr/                    ← Architecture Decision Records (34 entries)
├── architecture/           ← high-level design, C4 diagrams, observability, security
├── api/                    ← API contract, curl examples, Auth0 action code
├── reference/              ← technology glossary, methods catalogue, cost model
├── getting-started/        ← onboarding for a new contributor
├── ops/                    ← CI catalogue + timings + cost + runbooks
├── assets/                 ← banner, screenshots, images
├── examples/               ← sample API payloads + Prometheus dumps
└── archive/                ← deprecated decisions, kept for git-blame context
```

## Architecture decisions

Non-obvious architectural choices are captured in **ADRs** (Michael Nygard
format). The canonical index lives at [`adr/README.md`](adr/README.md) —
it lists every ADR with status, and the numbers are stable once merged.

## Getting started

| Doc                                                                  | Topic                                                                                          |
| -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| [`getting-started/dev-tooling.md`](getting-started/dev-tooling.md)    | First-day setup: required tools, Java/Node versions, IDE config, `./run.sh` cheatsheet.       |

## Architecture

| Doc                                                                       | Topic                                                                |
| ------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| [`architecture/overview.md`](architecture/overview.md)                    | Top-down walk-through: modules, component diagram, data-flow.       |
| [`architecture/c4-diagrams.md`](architecture/c4-diagrams.md)              | C4 diagrams (System / Container / Component) — Mermaid, GitHub-rendered. |
| [`architecture/environments-and-flows.md`](architecture/environments-and-flows.md) | Local / CI / kind / GKE environments + how data flows between them.   |
| [`architecture/observability.md`](architecture/observability.md)          | Metrics taxonomy, trace sampling, log levels, Grafana Cloud OTLP.   |
| [`architecture/security.md`](architecture/security.md)                    | Threat model, JWT + OAuth2 + API key, CVE handling, dep-check.      |

## API

| Doc                                                                    | Topic                                                                   |
| ---------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| [`api/api.md`](api/api.md)                                             | Resource-oriented API reference with curl examples.                     |
| [`api/contract.md`](api/contract.md)                                   | Versioning policy (`X-API-Version` header vs URL path), BC rules.      |
| [`api/auth0-action-roles.js`](api/auth0-action-roles.js)               | Reference Auth0 Action injecting realm roles into tokens.               |

## Reference (full technical glossary)

| Doc                                                                            | Topic                                                            |
| ------------------------------------------------------------------------------ | ---------------------------------------------------------------- |
| [`reference/technologies.md`](reference/technologies.md)                       | Long-form glossary — 196 entries with icon + official URL each.   |
| [`reference/methods-and-techniques.md`](reference/methods-and-techniques.md)   | Engineering methods catalogue (TDD, ADR-driven, trunk-based, …). |
| [`reference/cost-model.md`](reference/cost-model.md)                           | €/month breakdown for every component (GCP + GitLab + SaaS).      |

## Ops

### CI catalogue + philosophy

| Doc                                                  | Topic                                                                          |
| ---------------------------------------------------- | ------------------------------------------------------------------------------ |
| [`ops/ci-stages.md`](ops/ci-stages.md)               | Flat catalogue of every stage + job in `.gitlab-ci.yml` — what each does. |
| [`ops/ci-timings.md`](ops/ci-timings.md)             | Measured per-job CI/CD durations; refresh snippet included.                    |
| [`ops/ci-philosophy.md`](ops/ci-philosophy.md)       | **Why** three CI surfaces (GitLab canonical / GitHub mirror / Jenkinsfile).    |
| [`ops/ci-variables.md`](ops/ci-variables.md)         | Every `$VAR` the pipelines expect, with where to get it.                       |
| [`ops/jenkins.md`](ops/jenkins.md)                   | Jenkins parity demonstrator — where to lint, when to run.                      |
| [`ops/github-mirror.md`](ops/github-mirror.md)       | How the GitLab → GitHub mirror push works + why deploy keys won't.             |
| [`ops/cost-control.md`](ops/cost-control.md)         | €10/month budget gate, auto-destroy Cloud Function, cleanup commands.          |

### Runbooks (incident playbooks)

| Doc                                                                            | When to read                                                              |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------------- |
| [`ops/runbooks/README.md`](ops/runbooks/README.md)                             | Index + the "how to write a new runbook" template.                        |
| [`ops/runbooks/auto-merge-stuck.md`](ops/runbooks/auto-merge-stuck.md)         | MR armed for auto-merge but the pipeline keeps failing.                   |
| [`ops/runbooks/backend-503.md`](ops/runbooks/backend-503.md)                   | Spring `/actuator/health` returns 503 — which sub-probe is DOWN?         |
| [`ops/runbooks/compose-startup-fails.md`](ops/runbooks/compose-startup-fails.md) | `docker compose up` errors out — port conflict, mount path, plugin 404. |
| [`ops/runbooks/gke-cluster-boot-fails.md`](ops/runbooks/gke-cluster-boot-fails.md) | `bin/cluster/demo/up.sh` doesn't return — Argo CD stuck, ESO secret rotation.   |
| [`ops/runbooks/kafka-enrich-timeout.md`](ops/runbooks/kafka-enrich-timeout.md) | `/customers/{id}/enrich` returns 504 — request-reply timeout chain.       |
| [`ops/runbooks/ollama-bio-fallback.md`](ops/runbooks/ollama-bio-fallback.md)   | Bio comes back generic — Ollama down or model not pulled.                 |

Live technical reference (auto-generated, not committed):

| Source              | Where                                  | Regenerate with         |
| ------------------- | -------------------------------------- | ----------------------- |
| Javadoc             | `target/site/apidocs/`                 | `mvn site`              |
| OpenAPI spec        | `http://localhost:8080/v3/api-docs`    | Run the service         |
| Swagger UI          | `http://localhost:8080/swagger-ui.html`| Run the service         |
| Angular reference   | `../mirador-ui/docs/compodoc/`         | `npm run compodoc`       |

## Assets

- `assets/` — banner shown in the root README + screenshots referenced
  from the topic docs.
- `examples/` — sample API payloads, Prometheus scrape dumps, JSON
  fixtures used as reference data.

## Cross-repo

- Frontend companion docs: `../../mirador-ui/docs/` (same layout).
- Technology glossary for the frontend:
  [mirador-ui/docs/reference/technologies.md](https://gitlab.com/mirador1/mirador-ui/-/blob/main/docs/reference/technologies.md)

## Contributing

- Keep each topic file **self-contained** — cross-link to others rather
  than duplicating content.
- Update `architecture/overview.md` when adding a new module or changing
  a major data-flow path.
- Screenshots go into `assets/screenshots/`, referenced with paths
  relative to the file that embeds them.
- If a section outgrows its file, split by audience (dev, ops, sec)
  rather than by feature.
- **Every non-obvious decision gets an ADR** — see `adr/README.md` for
  the criteria.
