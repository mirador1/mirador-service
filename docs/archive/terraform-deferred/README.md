# Archived Terraform — Cloud SQL + Memorystore blocks

The HashiCorp resource blocks here used to live in
`deploy/terraform/gcp/main.tf`. They were removed on 2026-04-18 per:

- **ADR-0013** — in-cluster Postgres on GKE (supersedes the Cloud SQL
  choice).
- **ADR-0021** — cost-deferred industrial patterns (Memorystore Redis
  kept in-cluster for the demo).

## Files

- `main-with-cloudsql-memorystore.tf` — the full previous `main.tf`
  that provisions the VPC + GKE + Cloud SQL + Memorystore + all the
  IAM glue needed for Workload Identity-backed Cloud SQL Auth Proxy.

## Reactivation path

1. Create the GSM / GCP SA bindings for the Cloud SQL Auth Proxy (if
   not already done; the session runbook is in
   `docs/archive/gke-cloud-sql/README.md`).

2. Copy the relevant resource blocks back into
   `deploy/terraform/gcp/main.tf` (the file after the scope reset
   keeps only VPC + GKE + WIF).

3. Restore the associated variables
   (`db_name`, `db_user`, `db_password`, `db_tier`, `redis_tier`,
   `redis_memory_size_gb`) and outputs
   (`cloud_sql_instance_name`, `cloud_sql_private_ip`, `redis_host`,
   `redis_port`, `sql_proxy_service_account_email`) in
   `variables.tf` and `outputs.tf`.

4. `terraform apply` — the VPC resources are shared with the existing
   cluster, so the plan should only add the Cloud SQL + Memorystore
   + IAM resources.

5. Re-enable the `deploy/kubernetes/overlays/gke/cloud-sql-proxy.yaml`
   + `backend-cloudsql-sidecar-patch.yaml` (archived alongside) and
   drop `base/postgres` from the GKE overlay's resources list.

## Why keep this instead of deleting

Reactivation is a future-you concern — it's easier to copy a few
blocks back than to re-derive them from scratch. Archiving rather
than deleting also keeps the reasoning visible in the commit
history.
