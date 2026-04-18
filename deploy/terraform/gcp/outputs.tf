# =============================================================================
# Outputs — connection details needed downstream
#
# After `terraform apply`, run:
#   terraform output -json > /tmp/tf-out.json
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

output "workload_identity_pool" {
  description = "Workload Identity Pool — annotate K8s service accounts with this + GCP SA email"
  value       = "${var.project_id}.svc.id.goog"
}

# Cloud SQL + Memorystore outputs removed with the resource blocks (ADR-0013
# + ADR-0021). Reactivation path in docs/archive/terraform-deferred/.
