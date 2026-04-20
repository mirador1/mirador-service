# =============================================================================
# Outputs — consumed by CI scripts and humans running `terraform output`.
#
# Mirrors the GCP module's outputs where concepts map cleanly.
# =============================================================================

# =============================================================================
# Role        : AKS cluster name echoed for scripts.
# Why         : Parity with GCP's `gke_cluster_name`. Used by
#               `az aks get-credentials -n <this> -g <rg>` to populate
#               ~/.kube/config.
# Cost        : n/a.
# Related     : main.tf → azurerm_kubernetes_cluster.main.
# =============================================================================
output "aks_cluster_name" {
  description = "AKS cluster name — use with: az aks get-credentials"
  value       = azurerm_kubernetes_cluster.main.name
}

# =============================================================================
# Role        : Resource Group echo — needed alongside cluster name.
# Why         : `az aks get-credentials` requires both `-n <name>` and
#               `-g <rg>`. Avoids forcing the CI script to read tfvars.
# Cost        : n/a.
# Related     : variables.tf → var.resource_group_name.
# =============================================================================
output "resource_group_name" {
  description = "Resource Group containing every resource"
  value       = azurerm_resource_group.main.name
}

# =============================================================================
# Role        : Cluster FQDN — the stage-1 public API endpoint.
# Why         : AKS auto-generates `<dns_prefix>-<hash>.hcp.<region>.azmk8s.io`.
#               Output it so `kubectl` commands from CI can hit the API
#               server without hunting through the Azure portal.
# Cost        : n/a.
# Gotchas     : This is the API-server endpoint, not the app's URL.
#               The app's URL comes from an Ingress controller + Service
#               of type LoadBalancer (not provisioned in stage 1).
# Related     : main.tf → azurerm_kubernetes_cluster.main.fqdn.
# =============================================================================
output "cluster_fqdn" {
  description = "AKS control-plane FQDN (e.g. mirador-prod-xyz.hcp.westeurope.azmk8s.io)"
  value       = azurerm_kubernetes_cluster.main.fqdn
}

# =============================================================================
# Role        : Kubeconfig blob — alternative to `az aks get-credentials`.
# Why         : Useful in CI where `az login` is not available but
#               `TF_VAR_*` credentials feed the provider. Marked
#               `sensitive = true` so Terraform redacts it from stdout.
# Cost        : n/a.
# Gotchas     : - The emitted kubeconfig contains a client cert valid
#                 for 1 year. Renewal requires `az aks rotate-certs` or
#                 reapply.
#               - Printing `terraform output -json kube_config` in CI
#                 logs WILL leak the kubeconfig to job logs — redirect
#                 to a file + `chmod 600`.
# Related     : https://learn.microsoft.com/en-us/cli/azure/aks#az-aks-get-credentials
# =============================================================================
output "kube_config" {
  description = "Raw kubeconfig blob — write to ~/.kube/config (chmod 600)"
  value       = azurerm_kubernetes_cluster.main.kube_config_raw
  sensitive   = true
}

# =============================================================================
# Role        : Log Analytics workspace ID.
# Why         : Stage 2 dashboards / Grafana Azure Monitor datasource
#               need this workspace ID. Output now to save a portal
#               lookup later.
# Cost        : n/a.
# Related     : main.tf → azurerm_log_analytics_workspace.main.
# =============================================================================
output "log_analytics_workspace_id" {
  description = "Log Analytics workspace resource ID (for Grafana Azure Monitor datasource)"
  value       = azurerm_log_analytics_workspace.main.id
}

# =============================================================================
# Role        : Azure location echo.
# Why         : Same pattern as the AWS module — CI scripts consume
#               region from outputs instead of duplicating the value
#               across tfvars + CI vars.
# Cost        : n/a.
# Related     : variables.tf → var.location.
# =============================================================================
output "location" {
  description = "Azure location (region) where the stack is deployed"
  value       = var.location
}
