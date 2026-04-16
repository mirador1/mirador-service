# Mirador Service — Documentation

All long-form documentation for the Spring Boot backend. The root
`README.md` links here; everything more detailed than a paragraph lives
under this tree.

## Layout

```
docs/
├── README.md               ← you are here (index)
├── adr/                    ← Architecture Decision Records (11 entries)
├── architecture/           ← high-level design, observability, security model
├── api/                    ← API contract, curl examples, Auth0 action code
├── reference/              ← technology glossary (~1100 lines)
├── assets/                 ← banner, screenshots, images
└── examples/               ← sample API payloads + Prometheus dumps
```

## Architecture decisions

Non-obvious architectural choices are captured in **ADRs** (Michael Nygard
format). The canonical index lives at [`adr/README.md`](adr/README.md) —
it lists every ADR with status, and the numbers are stable once merged.

## Architecture

| Doc                                                                       | Topic                                                                |
| ------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| [`architecture/overview.md`](architecture/overview.md)                    | Top-down walk-through: modules, component diagram, data-flow.       |
| [`architecture/observability.md`](architecture/observability.md)          | Metrics taxonomy, trace sampling, log levels, Grafana Cloud OTLP.   |
| [`architecture/security.md`](architecture/security.md)                    | Threat model, JWT + OAuth2 + API key, CVE handling, dep-check.      |

## API

| Doc                                                                    | Topic                                                                   |
| ---------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| [`api/api.md`](api/api.md)                                             | Resource-oriented API reference with curl examples.                     |
| [`api/contract.md`](api/contract.md)                                   | Versioning policy (`X-API-Version` header vs URL path), BC rules.      |
| [`api/auth0-action-roles.js`](api/auth0-action-roles.js)               | Reference Auth0 Action injecting realm roles into tokens.               |

## Reference (full technical glossary)

| Doc                                                                   | Topic                                                            |
| --------------------------------------------------------------------- | ---------------------------------------------------------------- |
| [`reference/technologies.md`](reference/technologies.md)              | Long-form glossary — 199 entries over 22 categories.              |

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
