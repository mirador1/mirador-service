# `deploy/kubernetes/` — Kubernetes manifests

This directory holds the Kubernetes manifests applied by the `deploy:*`
jobs in `.gitlab-ci.yml`. Each subdirectory groups resources for a single
concern so the CI's `kubectl apply` loop can include/exclude folders based
on the target environment.

## Sub-directories

| Directory                | Contents                                                                                                                                              | When applied                                                                                 |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- |
| [`backend/`](backend/)   | Spring Boot backend: Deployment, Service, HPA, ConfigMap.                                                                                             | Every `deploy:*` target.                                                                    |
| [`frontend/`](frontend/) | Angular SPA: Deployment, Service. **Not applied by this repo's CI** — `mirador-ui` owns its own deployment and image registry.                       | Never from this repo. Kept for reference/documentation.                                      |
| [`infra/`](infra/)       | In-cluster stateful services for non-managed targets: Kafka, Redis, Postgres (StatefulSet with PVC).                                                  | `deploy:eks`, `deploy:aks`, `deploy:k3s`. **GKE skips `postgres.yaml`** (Cloud SQL takes over). |
| [`gke/`](gke/)           | GKE-specific patches: cert-manager RBAC fix for Autopilot, Cloud SQL Auth Proxy sidecar + Workload Identity service account.                          | `deploy:gke` only.                                                                          |
| [`local/`](local/)       | Kind-cluster–specific overlays (no TLS, no cert-manager, nip.io hostname).                                                                             | `run.sh k8s-local` only.                                                                    |

## Top-level files

| File              | Role                                                                                                                                                       |
| ----------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `namespace.yaml`  | Creates the `app`, `infra`, and `observability` namespaces. Applied first by every `deploy:*` job.                                                         |
| `ingress.yaml`    | Production Ingress (nginx) with TLS via cert-manager/Let's Encrypt. Routes `/api/*` → backend, `/*` → frontend. Uses `${K8S_HOST}` from CI vars.            |
| `kind-config.yaml`| Kind cluster config for `run.sh k8s-local` — port mappings to host + local registry connection.                                                            |

## Deploy pipeline (`.gitlab-ci.yml` → `.kubectl-apply` template)

The CI's `.kubectl-apply` template applies manifests in this order:

```
deploy/kubernetes/namespace.yaml           # always
deploy/kubernetes/stateful/redis.yaml         # always
deploy/kubernetes/stateful/kafka.yaml         # always
deploy/kubernetes/stateful/postgres.yaml      # NOT on GKE (skipped — uses Cloud SQL)
deploy/kubernetes/backend/configmap.yaml
deploy/kubernetes/backend/deployment.yaml
deploy/kubernetes/backend/service.yaml
deploy/kubernetes/backend/hpa.yaml
deploy/kubernetes/ingress.yaml
```

Then, on GKE only:

```
deploy/kubernetes/gke/cloud-sql-proxy.yaml # if CLOUD_SQL_INSTANCE CI var is set
```

`deploy/kubernetes/frontend/` is NOT applied by this repo — the frontend pipeline
(`mirador-ui` repo) handles its own deployment.

## What NOT to put here

- **Cloud infrastructure** (VPC, GKE cluster, Cloud SQL instance) → `deploy/terraform/gcp/`
- **Local Docker Compose configs** → `infra/`
- **Application source code** → `src/`

## Related docs

- `deploy/terraform/gcp/README.md` — how the cluster and managed services are
  provisioned.
- `.gitlab-ci.yml` (`.kubectl-apply` template, `deploy:gke` job) — exact
  apply commands and ordering.
