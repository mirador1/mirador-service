# `scripts/` — Developer convenience scripts

Shell scripts that automate day-to-day dev tasks. They are **not part of
the production build** and are never packaged into the JAR or Docker image.
Think of this directory as the project's toolbox.

## Scripts

| Script                   | What it does                                                                                                                                                            |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `deploy-local.sh`        | End-to-end local Kubernetes deploy: builds backend + frontend Docker images, pushes them to a local kind registry, creates a kind cluster, installs nginx-ingress, and deploys the full app stack. Used by `./run.sh k8s-local`. |
| `register-runner.sh`     | Registers the macOS Docker-Desktop–hosted `gitlab-runner` container against the GitLab project. Safe to re-run — uses a token from the environment.                     |
| `simulate-traffic.sh`    | Generates a realistic mix of API calls (login, CRUD customers, bios/todos) against `localhost:8080` to populate metrics/traces for the observability dashboards. Used by `./run.sh traffic [iterations] [pause]`. |
| `README.md`              | This file.                                                                                                                                                              |

## Conventions

- **Idempotent when possible** — a script should be safe to rerun. Use
  `set -euo pipefail` at the top so failures halt immediately.
- **No hard-coded paths** — accept overrides via env vars (`BASE_URL`,
  `CLUSTER_NAME`, etc.) with sensible defaults.
- **Document inputs** — each script has a header comment block listing
  arguments, env vars, and prerequisites.
- **No binaries** — Bash only (or a single-file Python in a pinch). Larger
  tooling belongs in its own language + build system.

## Where to put a new script

| Kind                                     | Location                               |
| ---------------------------------------- | -------------------------------------- |
| Local dev helper (this directory)        | `scripts/`                             |
| CI-only one-liner                        | Inline in `.gitlab-ci.yml` `script:`   |
| Docker/compose setup                     | `infra/<service>/` as a config file    |
| Kubernetes cluster bootstrap             | `deploy/kubernetes/` or inline in `deploy:*` job     |
| Multi-file/compiled tool                 | Separate sub-repo                      |

## Running

All scripts are designed to run from the project root:

```bash
./scripts/deploy-local.sh
./scripts/simulate-traffic.sh 100 1
./scripts/register-runner.sh
```

Most are also wrapped by `run.sh` commands for convenience:

```bash
./run.sh k8s-local       # wraps deploy-local.sh
./run.sh traffic 100 1   # wraps simulate-traffic.sh
```
