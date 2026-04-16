# ADR-0003: Cloud SQL over in-cluster Postgres on GKE

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

Running PostgreSQL as a K8s StatefulSet works fine on a long-lived node
pool but is a poor fit for **GKE Autopilot**, which:

- Uses ephemeral nodes that can be recycled for bin-packing efficiency.
- Charges per-pod for CPU/memory on a burstable model — persistent,
  low-utilisation Postgres pods end up more expensive than Cloud SQL.
- Requires careful PVC re-attachment orchestration on node changes, which
  adds failover latency.

We also want:
- Point-in-time recovery and automated backups without writing our own
  velero/restic pipeline.
- Automatic minor-version patching.
- Private IP (no egress NAT charges, no TLS overhead on the hot path).

## Decision

On GKE (production), use **Cloud SQL PostgreSQL 17** with the
[Cloud SQL Auth Proxy sidecar](https://cloud.google.com/sql/docs/postgres/connect-auth-proxy)
pattern:

```
Spring Boot → localhost:5432 → cloud-sql-proxy sidecar → Cloud SQL (private IP)
```

The sidecar authenticates via **Workload Identity** — no password, no key
file in the pod. The K8s ServiceAccount is annotated with the GCP SA, and
IAM binds the two together.

For all other targets (local kind, eks, aks), we continue to use the
in-cluster `StatefulSet` from `deploy/kubernetes/base/postgres/`.

Implementation lives in `deploy/kubernetes/overlays/gke/`:
- `cloud-sql-proxy.yaml` — ServiceAccount + GKE ConfigMap override (DB_HOST=127.0.0.1)
- `backend-cloudsql-sidecar-patch.yaml` — strategic merge patch adding
  the sidecar container to the backend Deployment

## Consequences

### Positive

- Backups, PITR, HA, patching: all managed by Google.
- No password to rotate. IAM handles auth. Revoking access = editing an
  IAM binding.
- Encrypted, in-VPC tunnel. No TCP-over-internet.
- Scales independently of the app — can upgrade Cloud SQL tier without
  restarting pods.

### Negative

- **Cost.** Cloud SQL is billed 24/7 (~$35/month for `db-g1-small`). We
  mitigate by **pausing the instance between test runs** — explicit
  policy, documented in the global CLAUDE.md.
- **Lock-in.** Migrating off GCP means re-provisioning a Postgres
  elsewhere. Acceptable: the JDBC layer is unchanged, only infrastructure
  is GCP-specific.
- **Sidecar overhead.** ~30 MiB RSS, 5-10m CPU per pod. Measured on
  staging, fits within the backend pod's budget.

### Neutral

- The sidecar image is pinned (`gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.15.1`).
  We bump it deliberately — never track `:latest`.

## Alternatives considered

### Alternative A — In-cluster Postgres on Autopilot

Rejected: PVC re-attachment on node eviction takes 30-60s; during that
window the app is down. Backups require extra tooling.

### Alternative B — AlloyDB

Rejected: 3-4× more expensive than Cloud SQL; we don't need HTAP
performance for a CRUD app.

### Alternative C — External Postgres (managed by a third party)

Rejected: adds another vendor relationship and a second egress path out
of GCP. Simpler to stay within one cloud when we're already there.

## References

- ADR-0002 — Kustomize overlay structure enabling per-target Postgres choice.
- `deploy/kubernetes/overlays/gke/cloud-sql-proxy.yaml` — the proxy setup.
- [Connect from GKE using Workload Identity](https://cloud.google.com/sql/docs/postgres/connect-kubernetes-engine)
