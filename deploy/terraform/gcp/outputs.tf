# =============================================================================
# Outputs — connection details needed to configure the K8s ConfigMap + Secrets
#
# After `terraform apply`, run:
#   terraform output -json > /tmp/tf-out.json
# Then inject into kubectl:
#   DB_HOST=$(jq -r .cloud_sql_private_ip.value /tmp/tf-out.json)
#   REDIS_HOST=$(jq -r .redis_host.value /tmp/tf-out.json)
# =============================================================================

output "gke_cluster_name" {
  description = "GKE Autopilot cluster name — use with: gcloud container clusters get-credentials"
  value       = google_container_cluster.autopilot.name
}

output "gke_cluster_endpoint" {
  description = "GKE cluster control-plane endpoint (HTTPS)"
  value       = google_container_cluster.autopilot.endpoint
  sensitive   = true
}

output "cloud_sql_instance_name" {
  description = "Cloud SQL instance connection name — used in the Cloud SQL Auth Proxy --instance flag"
  value       = google_sql_database_instance.postgres.connection_name
}

output "cloud_sql_private_ip" {
  description = "Cloud SQL private IP — set as DB_HOST in the backend ConfigMap (via Cloud SQL Auth Proxy, use 127.0.0.1)"
  value       = google_sql_database_instance.postgres.private_ip_address
}

output "redis_host" {
  description = "Memorystore Redis private IP — set as REDIS_HOST in the backend ConfigMap"
  value       = google_redis_instance.cache.host
}

output "redis_port" {
  description = "Memorystore Redis port"
  value       = google_redis_instance.cache.port
}

output "sql_proxy_service_account_email" {
  description = "GCP service account email for the Cloud SQL Auth Proxy (used in Workload Identity annotation)"
  value       = google_service_account.sql_proxy.email
}

output "workload_identity_pool" {
  description = "Workload Identity Pool — annotate K8s service accounts with this + GCP SA email"
  value       = "${var.project_id}.svc.id.goog"
}
