# =============================================================================
# Outputs — consumed by CI scripts and humans running `terraform output`.
#
# Mirrors the GCP module's outputs where concepts map cleanly.
# =============================================================================

# =============================================================================
# Role        : Kapsule cluster ID — Scaleway uses UUIDs, not names, for
#               most API calls.
# Why         : `scw k8s kubeconfig install <cluster-id>` is the canonical
#               way to populate ~/.kube/config. The ID is visible only in
#               the API; outputting here avoids a round-trip to the console.
# Cost        : n/a.
# Related     : main.tf → scaleway_k8s_cluster.main.id.
# =============================================================================
output "kapsule_cluster_id" {
  description = "Kapsule cluster UUID — use with: scw k8s kubeconfig install"
  value       = scaleway_k8s_cluster.main.id
}

# =============================================================================
# Role        : Cluster name echo.
# Why         : Parity with GCP's `gke_cluster_name`. Useful for log
#               correlation and display in CI UIs where a UUID is opaque.
# Cost        : n/a.
# Related     : variables.tf → var.cluster_name.
# =============================================================================
output "kapsule_cluster_name" {
  description = "Kapsule cluster name"
  value       = scaleway_k8s_cluster.main.name
}

# =============================================================================
# Role        : Cluster's API server URL.
# Why         : Kubernetes clients (kubectl, Helm, Argo CD) all hit this
#               URL. Scaleway generates `<cluster-id>.api.k8s.<region>.scw.cloud`
#               automatically; this output surfaces it.
# Cost        : n/a.
# Gotchas     : Publicly reachable by default. Network policies and
#               RBAC are the only perimeter until stage 2 adds private
#               networking (Kapsule Prod + VPC).
# Related     : main.tf → scaleway_k8s_cluster.main.apiserver_url.
# =============================================================================
output "cluster_apiserver_url" {
  description = "Kapsule API server URL"
  value       = scaleway_k8s_cluster.main.apiserver_url
}

# =============================================================================
# Role        : Raw kubeconfig emitted by Scaleway.
# Why         : Alternative to `scw k8s kubeconfig install` for CI
#               environments without the scw CLI. Marked sensitive so
#               Terraform redacts from stdout.
# Cost        : n/a.
# Gotchas     : - kubeconfig contains a client cert + token with full
#                 cluster-admin rights. Never print in CI logs.
#               - The cert expires ~1 year after apply; rotate via
#                 reapply or scw.
# Related     : main.tf → scaleway_k8s_cluster.main.kubeconfig.
# =============================================================================
output "kube_config" {
  description = "Raw kubeconfig blob — write to ~/.kube/config (chmod 600)"
  value       = scaleway_k8s_cluster.main.kubeconfig[0].config_file
  sensitive   = true
}

# =============================================================================
# Role        : Region echo for scripts.
# Why         : Same pattern as AWS/Azure modules — avoid duplicating
#               the region value between tfvars and CI variables.
# Cost        : n/a.
# Related     : variables.tf → var.region.
# =============================================================================
output "region" {
  description = "Scaleway region where the stack is deployed"
  value       = var.region
}
