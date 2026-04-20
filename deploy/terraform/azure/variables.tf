# =============================================================================
# Terraform variables — Azure reference implementation (AKS).
#
# Mirrors the GCP module's variables adapted to Azure concepts (Resource
# Group, Location, VM size vs machine type).
#
# Set via terraform.tfvars (git-ignored) or TF_VAR_* env vars in CI.
# Related: main.tf (consumers), README.md (apply instructions).
# =============================================================================

# =============================================================================
# Role        : Azure region ("location" in Azure terminology).
# Why         : Default `westeurope` (Netherlands) — the Azure equivalent
#               of GCP europe-west1 for EU data-sovereignty parity.
#               Alternatives: `francecentral` (Paris, slightly more
#               expensive, lower latency from France), `northeurope`
#               (Ireland, cheapest EU region on some SKUs).
# Cost        : n/a (identifier). Pricing is regional; Standard_B2s
#               ranges from ~€27/month (northeurope) to ~€32/month
#               (francecentral) on always-on.
# Gotchas     : Azure region names are lowercase no-hyphens
#               (`westeurope` NOT `west-europe`). Typos here fail with
#               `Invalid Resource Group location`.
# Related     : https://azure.microsoft.com/en-us/global-infrastructure/locations/
# =============================================================================
variable "location" {
  description = "Azure region (e.g. westeurope, francecentral, northeurope)"
  type        = string
  default     = "westeurope"
}

# =============================================================================
# Role        : Resource Group name — Azure's per-deployment namespace.
# Why         : Default `mirador-prod` mirrors the cluster naming across
#               the three cloud modules. Azure RG names are account-wide
#               unique (within a subscription).
# Cost        : n/a (identifier; Resource Groups are free).
# Gotchas     : - Cannot rename; recreating the RG recreates everything
#                 inside. Treat as a one-time decision.
#               - Max 90 chars, alphanumeric + `_.-`.
# Related     : main.tf → azurerm_resource_group.main.
# =============================================================================
variable "resource_group_name" {
  description = "Azure Resource Group name — will contain every resource"
  type        = string
  default     = "mirador-prod"
}

# =============================================================================
# Role        : AKS cluster name — also used as prefix for VNet, subnet,
#               Log Analytics workspace, and as `dns_prefix`.
# Why         : Default matches the GCP `cluster_name = mirador-prod` for
#               cross-cloud consistency. `dns_prefix` becomes part of the
#               cluster's FQDN, so it's publicly visible — don't put
#               secrets in it.
# Cost        : n/a (identifier).
# Gotchas     : Cluster name max 63 chars, alphanumeric + hyphens, must
#               start with a letter. Azure silently truncates longer
#               names and that breaks `az aks get-credentials`.
# Related     : main.tf (everywhere — name prefix).
# =============================================================================
variable "cluster_name" {
  description = "AKS cluster name — used as prefix for VNet, subnet, logs, DNS"
  type        = string
  default     = "mirador-prod"
}

# =============================================================================
# Role        : VM size for AKS node pool.
# Why         : Default `Standard_B2s` (2 vCPU, 4 GB RAM, burstable) is
#               the cheapest Azure VM size that meets AKS's minimum
#               requirement (2 vCPU). Burstable B-series tolerates spiky
#               JVM cold starts without breaking the bank on a quiet
#               cluster. For production, bump to Standard_D2s_v5 (2 vCPU,
#               8 GB, non-burstable) or D4s_v5 (4 vCPU, 16 GB).
# Cost        : Standard_B2s: ~€30/month always-on (westeurope). Ephemeral
#               pattern brings this to <€1/month.
# Gotchas     : - Changing `vm_size` on an existing node pool is NOT
#                 supported; requires pool replacement. Plan accordingly.
#               - Not every region has every VM size. `Standard_B2s` is
#                 widely available but rare SKUs like Standard_DC*s
#                 (confidential compute) are scarce.
# Related     : https://azure.microsoft.com/en-us/pricing/vm-selector/
# =============================================================================
variable "node_vm_size" {
  description = "VM size for AKS node pool (e.g. Standard_B2s, Standard_D2s_v5)"
  type        = string
  default     = "Standard_B2s"
}

# =============================================================================
# Role        : Number of nodes in the default (system) pool.
# Why         : Default 1 matches the single-replica demo policy
#               (ADR-0014). Autoscaler is off — single node means no
#               HPA headroom, which is fine for a reference module.
# Cost        : Linear scaling. 1 node = ~€30/month; 3 nodes = ~€90/month.
# Gotchas     : AKS recommends a minimum of 3 nodes for production to
#               survive a zonal fault. Single-node is for demos only.
# Related     : ADR-0014 (single-replica for demo), main.tf → node_count.
# =============================================================================
variable "node_count" {
  description = "Node count in the default AKS node pool"
  type        = number
  default     = 1
}

# =============================================================================
# Role        : Public hostname — stage 2 (not consumed in main.tf yet).
# Why         : Mirrors var.app_host in the GCP module. Reserved for
#               when the AKS Ingress gets an Application Gateway + DNS
#               record wired (stage 2).
# Cost        : n/a (string). Application Gateway adds ~€25-35/month
#               (Standard_v2 or WAF_v2 tier).
# Gotchas     : The cluster's auto-generated FQDN is available via
#               `terraform output cluster_fqdn` and is the stage-1 access
#               point.
# Related     : variables.tf (GCP module) → var.app_host.
# =============================================================================
variable "app_host" {
  description = "Public hostname for the app (stage 2 — not consumed yet)"
  type        = string
  default     = ""
}
