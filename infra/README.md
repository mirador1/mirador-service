# `infra/` — Local dev infrastructure configs

This directory holds **configuration files mounted into the local Docker
Compose stack**. Nothing here deploys to production — production
infrastructure lives under `deploy/terraform/gcp/` and `deploy/kubernetes/`.

Each sub-directory wraps a single service running in `docker-compose.yml`
(or `docker-compose.observability.yml`) and contains only the files that
customise that service — init scripts, config overrides, dashboard
definitions, or TLS certs. The naming mirrors the compose service name
so the mount paths are obvious at a glance.

## Sub-directories

| Directory                 | Service(s)                                    | Purpose                                                                 |
| ------------------------- | --------------------------------------------- | ----------------------------------------------------------------------- |
| [`keycloak/`](keycloak/)  | `keycloak`                                    | Realm exports (`realm-dev.json`, `realm-prod.json`) auto-imported on container start. |
| [`nginx/`](nginx/)        | `compodoc`, `maven-site`                      | Static-file reverse proxies serving Angular Compodoc and Maven site.    |
| [`observability/`](observability/) | `grafana`, `otel-collector`, `cors-proxy`, `prometheus` | LGTM stack: Grafana dashboards, OTel collector override, CORS reverse-proxies for browser access to Loki/Docker, Prometheus scrape config. |
| [`postgres/`](postgres/)  | `db`                                          | One-shot SQL init scripts (SonarQube database creation).                |

## Why a dedicated `infra/` folder?

- **Mount paths are short**: `./infra/keycloak/realm-dev.json:/opt/...` instead
  of burying configs under `docs/` or `src/`.
- **One concern per sub-dir**: editing the Grafana provisioning never risks
  breaking Keycloak's realm import.
- **Easy to exclude**: `infra/` is entirely irrelevant to the JAR build —
  `.dockerignore` excludes it from the image context so builds are faster.

## Conventions

- Every sub-directory has a `README.md` describing its purpose and
  file-by-file content.
- Config filenames should match what the tool expects (e.g. `realm-*.json`,
  `prometheus.yml`, `servers.json`) so mount paths need no renaming.
- No secrets live here — demo-grade credentials for local Docker Compose
  only. Production secrets come from K8s Secrets + GitLab CI variables.
- Scripts belong in `scripts/`, not here (see `scripts/simulate-traffic.sh`
  for an example moved out of this folder).

## Out of scope

- **Kubernetes manifests** → `deploy/kubernetes/` (separate target, different mount semantics).
- **Terraform code** → `deploy/terraform/` (provisions cloud infrastructure, not
  container configs).
- **Dev scripts** → `scripts/` (e.g. traffic simulators, register-runner helpers).
