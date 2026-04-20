# =============================================================================
# Terraform variables — Scaleway reference implementation (Kapsule).
#
# Mirrors the GCP module's variables adapted to Scaleway concepts
# (region = fr-par / nl-ams / pl-waw, node_type instead of machine_type).
#
# Set via terraform.tfvars (git-ignored) or TF_VAR_* env vars in CI.
# Scaleway credentials themselves come from SCW_* env vars, not these.
#
# Related: main.tf (consumers), README.md (apply instructions).
# =============================================================================

# =============================================================================
# Role        : Scaleway region — picks the data centre.
# Why         : Default `fr-par` (Paris) — optimal latency from France
#               and EU-sovereignty anchor (French company, French DC).
#               Alternatives:
#                 nl-ams  — Amsterdam, EU-based, ~10 ms from Paris.
#                 pl-waw  — Warsaw, cheapest region for some SKUs,
#                           ~30 ms from Paris.
# Cost        : n/a (identifier). Pricing is identical across Scaleway
#               regions for Kapsule and VM SKUs; only egress fees may
#               differ.
# Gotchas     : - Scaleway region is `fr-par` not `fr-par-1`; `fr-par-1`
#                 is the zone within the region. Mixing them breaks
#                 the provider.
#               - Multi-region is NOT supported in Kapsule — one cluster,
#                 one region.
# Related     : https://www.scaleway.com/en/docs/console/account/reference-content/products-availability/
# =============================================================================
variable "region" {
  description = "Scaleway region (fr-par, nl-ams, pl-waw)"
  type        = string
  default     = "fr-par"
}

# =============================================================================
# Role        : Kapsule cluster name.
# Why         : Default `mirador-prod` matches the cross-cloud convention.
#               Visible in `kubectl config get-contexts` after
#               `scw k8s kubeconfig install`.
# Cost        : n/a (identifier).
# Gotchas     : Max 63 chars, alphanumeric + hyphens. Cannot be changed
#               after creation.
# Related     : main.tf → scaleway_k8s_cluster.main.name.
# =============================================================================
variable "cluster_name" {
  description = "Kapsule cluster name"
  type        = string
  default     = "mirador-prod"
}

# =============================================================================
# Role        : Kubernetes version pinned on the cluster.
# Why         : Explicit pin (1.31) makes plans repeatable. Scaleway's
#               supported versions track upstream K8s with ~3-month lag;
#               check `scw k8s version list` before bumping. Floating
#               `latest` would silently upgrade on apply, which breaks
#               the "ephemeral cluster = exactly what was tested" model.
# Cost        : n/a (string).
# Gotchas     : - Pinning a version Scaleway has dropped = apply failure.
#                 Scaleway deprecates old minor versions ~12 months after
#                 upstream.
#               - Cluster upgrades across 2 minor versions require an
#                 intermediate hop (1.30 → 1.31 → 1.32).
# Related     : auto_upgrade block in main.tf (disabled to prevent drift).
# =============================================================================
variable "kubernetes_version" {
  description = "Kubernetes version for the Kapsule cluster (e.g. 1.31)"
  type        = string
  default     = "1.31"
}

# =============================================================================
# Role        : VM type for the Kapsule node pool.
# Why         : Default `DEV1-S` is the cheapest Scaleway VM that meets
#               Kapsule's minimum spec (2 vCPU, 2 GB). It's a burstable
#               Atom-class node — good enough for a demo, not for prod.
#               Step-ups:
#                 DEV1-M  — 2 vCPU, 4 GB, ~€16/month (extra JVM headroom).
#                 GP1-S   — 4 vCPU, 8 GB, ~€35/month (general purpose).
#                 PRO2-XS — 2 vCPU, 8 GB, ~€27/month (prod-grade CPU).
# Cost        : DEV1-S = ~€0.014/h ≈ €10/month always-on.
# Gotchas     : - Node type cannot be changed on an existing pool; new
#                 pool + drain is the only path.
#               - Not every type is available in every region. `scw
#                 instance server-type list` gives the authoritative list.
# Related     : https://www.scaleway.com/en/pricing/?tags=compute
# =============================================================================
variable "node_type" {
  description = "Scaleway VM type for Kapsule nodes (DEV1-S, DEV1-M, GP1-S, PRO2-XS)"
  type        = string
  default     = "DEV1-S"
}

# =============================================================================
# Role        : Number of nodes in the default pool.
# Why         : Default 1 matches the single-replica demo policy
#               (ADR-0014). Autoscaler is off on the pool.
# Cost        : Linear. 1 × DEV1-S = ~€10/month; 3 × DEV1-S = ~€30/month.
# Gotchas     : Kapsule Dev tier control plane is single-AZ. Adding
#               more nodes does NOT add control-plane HA.
# Related     : main.tf → scaleway_k8s_pool.system.size.
# =============================================================================
variable "node_count" {
  description = "Node count in the system pool"
  type        = number
  default     = 1
}

# =============================================================================
# Role        : Public hostname — stage 2 (not consumed in main.tf yet).
# Why         : Mirrors var.app_host in the GCP module. Reserved for
#               when a Scaleway LB + DNS record gets wired (stage 2).
# Cost        : n/a (string). Scaleway LB: ~€9/month for LB-S.
# Gotchas     : Stage 1 access is via `kubectl port-forward` (same
#               pattern as ADR-0025 for the GCP cluster).
# Related     : variables.tf (GCP module) → var.app_host.
# =============================================================================
variable "app_host" {
  description = "Public hostname for the app (stage 2 — not consumed yet)"
  type        = string
  default     = ""
}
