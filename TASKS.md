# Mirador Service — Persistent Task Backlog

<!--
  This file is the source of truth for pending work across sessions.
  Claude must READ this file at the start of every session.
  Claude must UPDATE this file whenever a task is added, started, or completed.
  Format: - [ ] pending   - [~] in progress   - [x] done (keep last 10 done for context)
-->

## Pending — Kubernetes & Cloud deployment

- [ ] **HTTPS — first cert issuance** — cert-manager + letsencrypt-prod ClusterIssuer
      are deployed and READY. The TLS cert will be auto-issued on the next successful
      `deploy:gke` run (Ingress has cert-manager.io/cluster-issuer annotation).
      CORS_ALLOWED_ORIGINS is already set to `https://${K8S_HOST}` in CI.
      **Action**: just trigger a deploy:gke pipeline on main (push or retry).

- [ ] **Cloud SQL Auth Proxy** — deploy:gke CI job now conditionally applies the sidecar
      when `CLOUD_SQL_INSTANCE` CI variable is set. Steps to enable:
      1. Create GCS state bucket: `gsutil mb -p $GCP_PROJECT gs://$GCP_PROJECT-tf-state`
      2. Run `terraform apply` to provision Cloud SQL + VPC peering
      3. Set `CLOUD_SQL_INSTANCE=$GCP_PROJECT:$GKE_REGION:mirador-db` as GitLab CI variable
      4. Next deploy:gke will auto-apply the proxy sidecar + DB_HOST override

- [ ] **Managed Kafka on GCP** — Google Cloud Managed Kafka (GA 2024, Kafka-compatible):
      set `kafka_enabled = true` in terraform/gcp/terraform.tfvars; see terraform/gcp/kafka.tf.

- [ ] **Terraform state bucket** — `terraform-plan` CI job fails because the GCS state
      bucket doesn't exist yet. Create it once:
      `gsutil mb -p $GCP_PROJECT -l europe-west1 gs://$GCP_PROJECT-tf-state`
      `gsutil versioning set on gs://$GCP_PROJECT-tf-state`

## Recently Completed

- [x] Pipeline fixes (2026-04-16): trivy image → aquasecurity/trivy, removed
      configuration-snippet annotation (blocked by nginx-ingress ≥1.9 on GKE),
      fixed duplicate terraform backend block, cleaned deploy-local.sh.
- [x] Cloud SQL Auth Proxy CI wiring — deploy:gke conditionally applies sidecar
      when CLOUD_SQL_INSTANCE env var is set.
- [x] MR !38 merged — GitLab Code Quality widget (SpotBugs+PMD+Checkstyle) + Semgrep.
- [x] Auth0 backend wiring — audience validation + role extraction fallback.
- [x] Grafana Cloud OTLP push — direct push via OTel, no DaemonSet (GKE Autopilot).
- [x] DuckDNS domain: mirador1.duckdns.org → 34.52.233.183.
- [x] SonarCloud migration + SonarQube BLOCKER/CRITICAL fixes.
- [x] K8s local test (kind) + Terraform GCP infra (VPC, GKE, Cloud SQL, Redis, Kafka).
- [x] All Maven site enrichments (trivy, licenses, cyclomatic complexity, slowest tests,
      untested classes, dependency freshness/tree/conflicts, startup time, pipelines, branches).
- [x] Pitest 1.23.0 (ASM 9.8 for Java 25), Compodoc, GitLab link in quality page.
