# Mirador Service — Persistent Task Backlog

<!--
  This file is the source of truth for pending work across sessions.
  Claude must READ this file at the start of every session.
  Claude must UPDATE this file whenever a task is added, started, or completed.
  Format: - [ ] pending   - [~] in progress   - [x] done (keep last 10 done for context)
-->

## In Progress

- [~] **deploy:gke first run** — Pipeline running on MR !39 (docker-build OOM fix).
      Once merged, main pipeline will build, then deploy:gke runs → cert-manager emits
      Let's Encrypt TLS cert automatically → HTTPS live on mirador1.duckdns.org.
      Monitored by cron job (every 3 min) to detect failures.

## Pending — Kubernetes & Cloud deployment

- [ ] **Terraform apply on GCP infra** — State bucket exists
      (gs://project-8d6ea68c-33ac-412b-8aa-tf-state, versioning on).
      After MR !39 merges, terraform-plan should succeed. Then manually trigger
      terraform-apply job (it's gated by `when: manual`) to provision:
      VPC + GKE Autopilot + Cloud SQL PostgreSQL 17 + Memorystore Redis + IAM SAs.

- [ ] **Cloud SQL Auth Proxy enablement** — After terraform-apply succeeds:
      1. `terraform output -raw cloud_sql_instance_name` → get connection name
      2. Set GitLab CI variable `CLOUD_SQL_INSTANCE=$GCP_PROJECT:$GKE_REGION:mirador-db`
      3. Next deploy:gke auto-injects the sidecar (already wired in .gitlab-ci.yml).

- [ ] **Managed Kafka on GCP (OPTIONAL, ~$35/day)** — Deferred: not a simple toggle.
      Requires uncommenting ~70 lines in terraform/gcp/kafka.tf + SASL config in
      backend configmap + secret creation. See migration path at end of kafka.tf.

## Recently Completed

- [x] Created Terraform state GCS bucket (versioning enabled) — already existed.
- [x] Pipeline fixes (2026-04-16): trivy → aquasecurity/trivy, suppression nginx
      configuration-snippet, fix duplicate terraform backend, strategic merge patch
      for cloud-sql-proxy sidecar, Maven heap cap in Dockerfile (OOM fix).
- [x] Cloud SQL Auth Proxy CI wiring — deploy:gke conditionally applies sidecar
      when CLOUD_SQL_INSTANCE env var is set.
- [x] CORS headers hardened (explicit allowlist instead of wildcard).
- [x] RateLimitingFilter hardened (IP validation + 50k bucket cap) against
      X-Forwarded-For spoofing DoS.
- [x] MR !38 merged — GitLab Code Quality widget + Semgrep.
- [x] Auth0 backend wiring — audience validation + role extraction fallback.
- [x] Grafana Cloud OTLP push — direct push via OTel, no DaemonSet.
- [x] SonarCloud migration + SonarQube BLOCKER/CRITICAL fixes.
- [x] All Maven site enrichments (trivy, licenses, cyclomatic complexity,
      slowest tests, untested classes, dependency freshness/tree/conflicts,
      startup time, pipelines, branches).
