# ADR-0013: In-cluster Postgres on GKE (revisits ADR-0003)

- **Status**: Accepted
- **Date**: 2026-04-18
- **Supersedes**: [ADR-0003 — Cloud SQL over in-cluster Postgres](0003-cloud-sql-over-in-cluster-postgres.md)

## Context

ADR-0003 (2026-04-16) recommended Cloud SQL for the GKE overlay, citing
point-in-time recovery, automated backups, Query Insights, and IAM
database authentication. Those are real Cloud SQL strengths — but once
the demo-scope constraints were written down explicitly, none of them
turned out to be load-bearing:

| Claim in ADR-0003 | Demo reality (2026-04-18) |
|---|---|
| Point-in-time recovery | Never exercised; no backup/restore in the demo scenario |
| Automated backups | Same — not demonstrated |
| Query Insights (top queries) | Replaceable by `pg_stat_statements` + a Grafana panel — and the Grafana stack is already part of the LGTM deploy (ADR-0012) |
| IAM DB authentication | Nice-to-have; the K8s Secret path works and is the canonical demo pattern for most clusters |
| UI editor (Cloud SQL Studio) | pgAdmin already runs in Docker locally; can be added as a small Deployment in the cluster later if needed |
| Managed patching | Every other overlay (local / eks / aks) runs `base/postgres` StatefulSet — keeping GKE self-hosted makes the deploy identical across targets |

The ~10 €/month active cost of `db-f1-micro` is small on its own, but:
- It's 10 € spent to deliver features that are not part of the demo.
- It adds an out-of-cluster component (Cloud SQL proxy sidecar + Workload
  Identity SA) whose configuration complicates the "same manifests
  everywhere" story.
- Terraform-managed Cloud SQL is another piece that needs to exist before
  `deploy:gke` can run successfully.

## Decision

**The GKE overlay deploys Postgres as an in-cluster StatefulSet, the same
way `local`, `eks` and `aks` do.** The Cloud SQL option stays archived
(documented reactivation path in `docs/archive/gke-cloud-sql/README.md`)
so it can be switched back on if the workload ever outgrows a single
StatefulSet pod.

## Consequences

Positive:
- Manifests are uniform across the four overlays — fewer patches to
  reason about, one less sidecar to explain in the demo.
- No Cloud SQL instance, no Cloud SQL Admin API, no `CLOUD_SQL_INSTANCE`
  GitLab CI variable, no Workload Identity binding for the DB — the whole
  managed-DB moving-parts tree disappears.
- `deploy:gke` becomes a drop-in of `kubectl apply -k overlays/gke` once
  the cluster has enough room for the pods (the StatefulSet's 10 GB PVC
  was already included in the SSD quota we tracked).
- Saves ~10 €/month of active Cloud SQL cost.

Negative:
- Backups and patching are now our problem. The compensation is that
  backups aren't in the demo scope — if they ever re-enter scope, a
  CronJob-based `pg_dump` to GCS is ~30 lines of YAML.
- Cloud SQL's Query Insights UI is gone. `pg_stat_statements` +
  `postgres-exporter` + a Grafana panel is a ~15-minute add if someone
  wants the same signal.
- No automatic failover between zones. The demo is explicitly single-
  replica (see [ADR-0014](0014-single-replica-for-demo.md)); HA can be
  re-introduced either through Cloud SQL HA or by adopting a Patroni /
  CNPG operator depending on whether managed or self-hosted is picked.

## Reactivation path

`docs/archive/gke-cloud-sql/README.md` keeps the 2 sidecar-patch YAML
files and step-by-step instructions to flip the overlay back to Cloud
SQL. The archive carries the argument that **Cloud SQL becomes the right
choice the moment any of these is true**:

- Point-in-time recovery becomes a real requirement.
- The DB outgrows a single StatefulSet pod.
- Compliance requires managed patch windows + audit logs.

## References

- `docs/archive/gke-cloud-sql/README.md` — reactivation runbook
- ADR-0003 (superseded) — the original Cloud SQL decision
- ADR-0014 — single-replica-for-demo, the sibling decision taken the
  same day
