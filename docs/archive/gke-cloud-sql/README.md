# Archived: Cloud SQL sidecar overlay fragments

These two files were part of `deploy/kubernetes/overlays/gke/` when the GKE
deployment targeted Google Cloud SQL for its PostgreSQL backend. They've been
moved here (not deleted) to keep the reactivation path available without
cluttering the active overlay.

## Why archived

The demo runs PostgreSQL as an in-cluster StatefulSet (the same `base/postgres`
manifest used by every other overlay — `local`, `eks`, `aks`). Cloud SQL added
~10 €/month of active charges for capabilities the demo doesn't need
(automated backups, point-in-time recovery, IAM authentication, Query Insights
— all available self-hosted with `pg_stat_statements` + Grafana + pgAdmin
running in Docker or the cluster).

## When to re-enable

Reach for Cloud SQL when one of these becomes true:

- Users want PITR (point-in-time recovery) without hand-rolling `pgbackrest`.
- The DB outgrows a single StatefulSet pod (read replicas, HA across zones).
- The team wants IAM database authentication instead of a K8s secret.
- Compliance requires managed patching windows and audit logs.

## How to re-enable (future you)

1. Uncomment the Cloud SQL resources in Terraform:
   ```
   deploy/terraform/gcp/main.tf  →  google_sql_database_instance "mirador"
   ```
   and add back `TF_VAR_db_password` / `TF_VAR_db_tier` in GitLab CI vars.

2. Run `terraform apply`, note the connection string:
   ```
   gcloud sql instances describe mirador-db \
     --format='value(connectionName)'
   ```
   and set the `CLOUD_SQL_INSTANCE` GitLab CI variable.

3. Move these two files back into the overlay and wire them into
   `deploy/kubernetes/overlays/gke/kustomization.yaml`:
   ```yaml
   resources:
     - ../../base          # drop ../../base/postgres
     - cert-manager-gke-fix.yaml
     - cloud-sql-proxy.yaml

   patches:
     - path: ingress-tls-patch.yaml
       target: { kind: Ingress, name: mirador-ingress }
     - path: backend-cloudsql-sidecar-patch.yaml
       target: { kind: Deployment, name: mirador }
   ```

4. Delete the in-cluster `postgresql` StatefulSet and its PVC:
   ```
   kubectl delete statefulset postgresql -n infra
   kubectl delete pvc postgres-data-postgresql-0 -n infra
   ```

5. Re-run `deploy:gke` and verify the backend reaches Cloud SQL via
   `127.0.0.1:5432` (the sidecar's local address).

The architecture supports both postures with the exact same
`mirador-backend` Deployment spec — only the overlay fragments change.
