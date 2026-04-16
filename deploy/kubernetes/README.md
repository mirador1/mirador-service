# `deploy/kubernetes/` — Kubernetes manifests (Kustomize layout)

This directory holds the Kubernetes manifests applied by the `deploy:*` jobs
in `.gitlab-ci.yml` and by `scripts/deploy-local.sh`. It follows the
**Kustomize base + overlays** pattern — the industry-standard way to keep
one source of truth while customising per target environment.

## Layout

```
deploy/kubernetes/
├── base/                          ← shared resources, no cluster-specifics
│   ├── kustomization.yaml           ← declares everything in the base
│   ├── namespace.yaml               ← app / infra / observability
│   ├── ingress.yaml                 ← HTTP-only (TLS added by overlays)
│   ├── backend/                     ← Spring Boot: Deployment, Service, HPA, ConfigMap
│   ├── frontend/                    ← Angular SPA: Deployment, Service
│   ├── stateful/                    ← in-cluster Kafka, Redis, Keycloak
│   └── postgres/                    ← OPTIONAL in-cluster Postgres (own mini-base)
│
├── overlays/
│   ├── local/                     ← kind cluster (deploy-local.sh)
│   │   ├── kustomization.yaml       includes ../../base + ../../base/postgres
│   │   └── images-pullpolicy-patch.yaml  (Never — kind loads images directly)
│   │
│   ├── gke/                       ← GKE Autopilot + Cloud SQL + Let's Encrypt
│   │   ├── kustomization.yaml
│   │   ├── cert-manager-gke-fix.yaml     (RBAC for Autopilot)
│   │   ├── cloud-sql-proxy.yaml          (ServiceAccount + DB_HOST override)
│   │   ├── ingress-tls-patch.yaml        (cert-manager + tls: block)
│   │   └── backend-cloudsql-sidecar-patch.yaml  (sidecar container)
│   │
│   ├── eks/                       ← AWS EKS + in-cluster Postgres (same shape as local)
│   │   └── kustomization.yaml
│   │
│   └── aks/                       ← Azure AKS + in-cluster Postgres (same shape as local)
│       └── kustomization.yaml
│
├── kind-config.yaml               ← kind cluster config for deploy-local.sh
└── README.md                      ← you are here
```

## How to apply

Everything goes through `kubectl apply -k` (Kustomize is bundled in `kubectl`
≥ 1.14 — no separate install needed):

```bash
# Local dev (kind)
./scripts/deploy-local.sh                               # wraps the command below
kubectl apply -k deploy/kubernetes/overlays/local

# Production GKE
kubectl apply -k deploy/kubernetes/overlays/gke

# AWS EKS / Azure AKS (manual triggers in CI)
kubectl apply -k deploy/kubernetes/overlays/eks
kubectl apply -k deploy/kubernetes/overlays/aks
```

CI pipelines (`.gitlab-ci.yml` → `.kubectl-apply` template) export
`K8S_OVERLAY=<name>` then run:

```bash
kubectl kustomize "deploy/kubernetes/overlays/${K8S_OVERLAY}" \
  | envsubst \
  | kubectl apply -f -
```

`envsubst` replaces `${K8S_HOST}`, `${IMAGE_REGISTRY}`, `${IMAGE_TAG}`,
`${UI_IMAGE_TAG}`, `${CLOUD_SQL_INSTANCE}`, `${GCP_PROJECT}` and
`${CORS_ALLOWED_ORIGINS}` in the rendered YAML stream. Kustomize itself
does **not** do variable substitution.

## How each overlay differs from the base

| Overlay | Adds                                                                 | Removes                   | Patches                                                                 |
| ------- | -------------------------------------------------------------------- | ------------------------- | ----------------------------------------------------------------------- |
| `local` | `base/postgres` (in-cluster StatefulSet)                              | none                      | `imagePullPolicy: Never` on mirador + customer-ui (kind loads directly) |
| `gke`   | `cert-manager-gke-fix.yaml`, `cloud-sql-proxy.yaml`                   | `base/postgres` (skipped) | Ingress: cert-manager + TLS · Deployment: Cloud SQL proxy sidecar       |
| `eks`   | `base/postgres`                                                       | none                      | none (cluster-specific IAM via Terraform)                               |
| `aks`   | `base/postgres`                                                       | none                      | none (cluster-specific identities via Terraform)                        |

## Why Postgres is in its own mini-base

GKE uses Cloud SQL, not an in-cluster DB. Keeping `postgres/` out of the main
base means `overlays/gke` doesn't have to explicitly remove it — it simply
doesn't reference it. The other overlays opt in by listing
`../../base/postgres` in their `resources:` block.

Kustomize requires every resource to live inside a kustomization tree, so
the mini-base has its own `kustomization.yaml` (just `resources: [postgres.yaml]`).

## Adding a new overlay

1. `mkdir deploy/kubernetes/overlays/<target>`
2. Create `kustomization.yaml` with `resources: [../../base]` plus anything
   target-specific.
3. If the target needs in-cluster Postgres, add `../../base/postgres` to
   `resources`. If it uses managed PostgreSQL, skip it and add an override
   ConfigMap that sets `DB_HOST` to the managed endpoint (see
   `overlays/gke/cloud-sql-proxy.yaml` for a reference).
4. Verify the build:
   ```bash
   kubectl kustomize deploy/kubernetes/overlays/<target> | kubectl apply --dry-run=client -f -
   ```
5. Wire a new `deploy:<target>` job in `.gitlab-ci.yml` that exports
   `K8S_OVERLAY=<target>` and calls `!reference [.kubectl-apply, script]`.

## What NOT to put here

- **Cloud infrastructure** (VPC, GKE/EKS/AKS cluster, Cloud SQL instance,
  IAM bindings) → `deploy/terraform/`
- **Local Docker Compose configs** (dev-only services) → `infra/`
- **Application source code** → `src/`

## Related docs

- `deploy/terraform/gcp/README.md` — how the GKE cluster and Cloud SQL
  instance are provisioned.
- `.gitlab-ci.yml` — the `.kubectl-apply` template and each `deploy:*` job.
- `scripts/deploy-local.sh` — wraps kind + Kustomize for one-command local
  deployment.
