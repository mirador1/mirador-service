# `deploy/` — Production deployment artifacts

All files under `deploy/` describe **how the application reaches
production** (or a production-equivalent environment). Everything here is
evaluated by the `deploy:*` and `terraform-*` CI jobs in
`.gitlab-ci.yml`; none of it affects local dev.

## Sub-directories

| Directory                         | Role                                                                                                                                                                  |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [`kubernetes/`](kubernetes/)      | K8s manifests applied to the target cluster. Organised by concern: `backend/`, `frontend/`, `stateful/` (Postgres/Kafka/Redis for non-managed targets), `gke/` (GKE-specific patches), `local/` (kind overlay). |
| [`terraform/`](terraform/)        | Terraform modules that provision the cloud infrastructure. Currently only `gcp/` — more providers could be added as siblings.                                         |

## Why group them?

Before this layout, K8s and Terraform lived at the repo root as siblings
of the application code. They are semantically **both "deploy"** — one
creates the infra, the other deploys workloads onto it — so grouping
them under a single parent makes the repo's concerns legible at a glance:

```
src/        ← application source
build/      ← build-time templates
infra/      ← local dev configs
deploy/     ← production deployment (Terraform + K8s)
docs/       ← human-targeted documentation
config/     ← static analyzer configs
scripts/    ← dev scripts
```

Each top-level folder has ONE concern. No homonyms between levels
(previously `k8s/infra/` clashed with `/infra/`; now `deploy/kubernetes/stateful/`).

## CI wiring

| Job in `.gitlab-ci.yml` | Uses                                                      |
| ----------------------- | --------------------------------------------------------- |
| `terraform-plan`        | `deploy/terraform/gcp/` — `terraform init` + `terraform plan` |
| `terraform-apply`       | `deploy/terraform/gcp/` — applies the plan (manual trigger) |
| `deploy:gke`            | `deploy/kubernetes/` (everything except `stateful/postgres.yaml` and `frontend/`) |
| `deploy:eks` / `:aks` / `:k3s` / `:fly` | `deploy/kubernetes/` + `deploy/kubernetes/stateful/postgres.yaml` |

## Related

- `infra/` — local Docker Compose configs (NOT a production concern).
- `scripts/deploy-local.sh` — wraps the `deploy/kubernetes/` apply loop
  for a kind cluster.
