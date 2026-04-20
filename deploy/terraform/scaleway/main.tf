# =============================================================================
# Terraform — Scaleway infrastructure for mirador (EU-sovereign reference)
#
# Status: REFERENCE / STAGE 1 — not applied against a billing account.
# Canonical target is GCP (see deploy/terraform/gcp/). This module exists
# to showcase an EU-sovereign, French-hosted alternative for teams that
# prioritise data residency over breadth-of-services.
#
# See ADR-0036 (multi-cloud Terraform posture) for the decision.
#
# Why Scaleway?
# ─────────────
# - **EU data sovereignty** — Scaleway S.A. is a French company owned by
#   Iliad (€1.2B EU-HQ parent). Data stays under GDPR jurisdiction only;
#   no CLOUD Act exposure like GCP/AWS/Azure (US-HQ hyperscalers).
# - **Cheapest managed Kubernetes in Europe for small clusters.** Kapsule
#   has a **free control plane** AND DEV-tier pricing at ~€10/month for a
#   1 × DEV1-S node (2 vCPU, 2 GB). That's half the GCP/AKS equivalent.
# - **Native Paris + Amsterdam + Warsaw regions.** Latency from France
#   ~2 ms, vs ~30 ms to GCP europe-west1 (Belgium).
# - **Simpler API surface than AWS/Azure/GCP** — ~20 services total,
#   easier to reason about the whole platform.
#
# Why not picked as the primary target?
# - Smaller ecosystem (no managed Kafka equivalent; Object Storage is
#   S3-compatible but missing some features).
# - No Workload Identity Federation equivalent — CI auth uses API tokens
#   (rotated manually), not OIDC.
# - No equivalent of GKE Autopilot / AKS Automatic (per-pod billing with
#   zero node management).
# - Documentation less mature than hyperscalers; some beta APIs move
#   faster than Terraform provider releases.
#
# What this module provisions (minimal stage-1):
#   - Kapsule (managed Kubernetes) cluster in fr-par.
#   - Single DEV1-S node pool (2 vCPU / 2 GB, ~€10/month always-on).
#
# What's deferred (stage 2):
#   - Managed PostgreSQL (€16/month Start tier) — bring-your-own today.
#   - Managed Redis — bring-your-own or self-hosted in-cluster.
#   - No managed Kafka available on Scaleway; use Google Managed Kafka
#     (cross-cloud) or self-host in-cluster Kafka.
#   - Private Networking + VPC + NAT Gateway — Kapsule Prod tier only.
#   - Kapsule Prod (~€72/month control plane) for HA control plane — Dev
#     tier is single-AZ.
#
# Related:
#   - variables.tf            — inputs (region, cluster name, node type)
#   - outputs.tf              — kubeconfig + cluster identifiers
#   - backend.tf              — local state (TODO: migrate to Scaleway
#                               Object Storage via S3 backend)
#   - README.md               — apply instructions + cost breakdown
#   - ADR-0036                — multi-cloud posture
#   - deploy/terraform/gcp/   — canonical target for comparison
# =============================================================================

# =============================================================================
# Role        : Terraform core + Scaleway provider version pin.
# Why         : Same reproducibility rationale as the other modules.
#               Scaleway provider 2.x is current stable (2024+); 1.x is
#               EOL. Constraint `~> 2.0` allows patch and minor bumps
#               automatically (provider follows semver strictly).
# Cost        : n/a (metadata only).
# Gotchas     : - Provider is maintained by Scaleway staff + HashiCorp
#                 community; resource coverage lags AWS/Azure/GCP by
#                 ~6 months on new features.
#               - Source is `scaleway/scaleway`, NOT `hashicorp/scaleway`.
#                 Typos here fail with "provider not found" at init.
# Related     : https://registry.terraform.io/providers/scaleway/scaleway/latest
# =============================================================================
terraform {
  required_version = ">= 1.8"

  required_providers {
    scaleway = {
      source  = "scaleway/scaleway"
      version = "~> 2.0"
    }
  }
}

# =============================================================================
# Role        : Scaleway provider configuration.
# Why         : Credentials come from env vars (SCW_ACCESS_KEY, SCW_SECRET_KEY,
#               SCW_DEFAULT_ORGANIZATION_ID, SCW_DEFAULT_PROJECT_ID) — NOT
#               committed here. Inline `access_key` / `secret_key` on the
#               provider is possible but discouraged; env-driven keeps
#               the module reusable across accounts.
#
#               region: fr-par (Paris). Alternatives: nl-ams (Amsterdam),
#               pl-waw (Warsaw). zone derived by provider from region
#               (fr-par-1 is the default). Kapsule clusters pick a zone
#               inside the region; single-zone in the Dev tier.
# Cost        : n/a (metadata only).
# Gotchas     : - SCW_DEFAULT_PROJECT_ID is required on every API call
#                 even though the provider accepts it as a resource-level
#                 override. Setting as env var avoids per-resource repetition.
#               - The provider's default `region = fr-par` works, but
#                 pinning it explicitly via var.region makes cross-region
#                 apply intentional.
# Related     : variables.tf → var.region.
# =============================================================================
provider "scaleway" {
  region = var.region
  # zone, organization_id, project_id, access_key, secret_key: all read
  # from environment by the provider. Set SCW_* env vars before apply.
}

# =============================================================================
# Role        : Kapsule (managed Kubernetes) cluster.
# Why         : - `type = "kapsule"` + no explicit tier defaults to the
#                 Dev-ish SLA. For prod SLA (99.95 %, multi-AZ control
#                 plane) bump to `type = "multicloud"` — €72/month.
#               - `version` pinned to 1.31 — widely supported on Scaleway
#                 as of 2026-04. Omit or set `null` to let Scaleway pick
#                 the latest; we prefer explicit for repeatable plans.
#               - `cni = "cilium"` — Scaleway's default, gives us
#                 NetworkPolicy enforcement out of the box. Alternatives:
#                 calico, flannel, kilo. Cilium is the "just works"
#                 choice for K8s-native policies.
#               - `auto_upgrade` disabled — matches the GCP STABLE
#                 release channel philosophy (no surprise upgrades
#                 mid-demo).
# Cost        : Control plane: €0 on Dev tier.
#               Node pool: paid separately below.
# Gotchas     : - Kapsule Dev is SINGLE-AZ for the control plane. Any
#                 zonal fault takes the API server offline. Prod tier
#                 (€72/month) fixes this — deferred for cost reasons.
#               - `version` must match Scaleway's supported list or apply
#                 fails. Check `scw k8s version list` before bumping.
#               - Changing `cni` after creation is NOT supported — rebuild
#                 the cluster.
# Related     : ADR-0022 (ephemeral pattern applies here too).
# =============================================================================
resource "scaleway_k8s_cluster" "main" {
  name    = var.cluster_name
  type    = "kapsule"
  version = var.kubernetes_version
  cni     = "cilium"

  # Scaleway provider 2.x requires this to be set explicitly — it tells
  # the provider whether to clean up LBs / volumes created via the K8s
  # API (CCM) on cluster destroy. `true` makes terraform destroy clean;
  # `false` leaves orphan LBs that silently keep billing (~€9/month each).
  # We pick `true` to match the ephemeral pattern's "leave no orphan"
  # discipline (see ADR-0022).
  delete_additional_resources = true

  # Auto-upgrade off: we want predictable Kubernetes versions during
  # live demos. Flip to true in long-lived production where the
  # upgrade-window friction outweighs the demo-time surprise.
  auto_upgrade {
    enable                        = false
    maintenance_window_start_hour = 3
    maintenance_window_day        = "monday"
  }

  # On Scaleway provider 2.x, node pools are declared as separate
  # `scaleway_k8s_pool` resources — no `default_pool` block at the
  # cluster root. See scaleway_k8s_pool.system below.

  tags = ["project:mirador", "env:reference", "managed:terraform"]
}

# =============================================================================
# Role        : Kapsule node pool — 1 × DEV1-S node for the demo.
# Why         : - `node_type = "DEV1-S"`: cheapest Scaleway VM that
#                 matches Kapsule's minimum (2 vCPU, 2 GB). DEV1-M
#                 (2 vCPU, 4 GB) is the next step if the JVM needs
#                 more headroom — ~€16/month instead of ~€10/month.
#               - `size = 1`: single-replica demo policy (ADR-0014).
#                 Autoscaling off; `min_size`/`max_size` omitted.
#               - `autohealing` on: Scaleway auto-replaces unhealthy
#                 nodes. Low-risk default.
#               - `container_runtime = "containerd"` — only supported
#                 choice on current Kapsule. Docker runtime was dropped
#                 in K8s 1.24.
# Cost        : DEV1-S: ~€0.014/hour × 730 = ~€10/month always-on.
#               Ephemeral pattern brings this to <€0.50/month.
# Gotchas     : - Bumping `node_type` on an existing pool is NOT
#                 supported by Scaleway — you replace the whole pool.
#                 For a live cluster, create a second pool with the new
#                 type + drain the old one.
#               - DEV1-S has only 20 GB of root disk; heavy image layers
#                 can fill it quickly. Bump to DEV1-M or GP1-S for real
#                 work.
# Related     : ADR-0014 (single-replica), cluster resource above.
# =============================================================================
resource "scaleway_k8s_pool" "system" {
  cluster_id  = scaleway_k8s_cluster.main.id
  name        = "system"
  node_type   = var.node_type
  size        = var.node_count
  autoscaling = false
  autohealing = true

  # Scaleway surfaces container runtime as a pool-level setting.
  # containerd is the only supported choice since K8s 1.24 dropped
  # dockershim.
  container_runtime = "containerd"

  tags = ["project:mirador", "pool:system"]
}
