# =============================================================================
# Terraform — Azure infrastructure for mirador (reference implementation)
#
# Status: REFERENCE / STAGE 1 — not applied against a subscription.
# Canonical target is GCP (see deploy/terraform/gcp/). This module exists
# so a reviewer or adopting team can see the project running on Azure
# without rewriting from scratch.
#
# See ADR-0036 (multi-cloud Terraform posture) for the decision.
#
# Why AKS, not Container Apps or App Service?
# ───────────────────────────────────────────
# Three Azure options for running the image:
#
# 1. **AKS (Azure Kubernetes Service)** — full K8s. Control plane is
#    **free** (like GKE, unlike EKS). You pay only for the node pool.
#    AKS Automatic (2024-06 GA) mirrors GKE Autopilot's per-pod billing.
#
# 2. **Azure Container Apps** — serverless containers, per-vCPU-second.
#    Simpler than AKS but no Argo CD, no ESO, no CRDs. Equivalent of
#    ECS Fargate on AWS.
#
# 3. **Azure App Service** — PaaS, JAR upload, not container-native.
#    Too far from the K8s story the project is meant to showcase.
#
# This module picks **AKS with a small node pool** (1× Standard_B2s):
# - Free control plane → no EKS-style €72/month floor.
# - Keeps the Kubernetes story intact (Argo CD, ESO, NetworkPolicy, HPA).
# - Single node is the cheapest always-on option (~€30/month).
# - Same trade-off as GKE Standard vs Autopilot — we accept node-pool
#   management in exchange for the predictable node cost.
#
# Alternative: AKS Automatic (per-pod billing like Autopilot).
# Not picked because (a) it's a newer API (2024-06 GA) with higher
# change-of-semantics risk during the portfolio's lifetime, (b) the
# reference nature of this module doesn't benefit from Automatic's
# "zero node config" value proposition.
#
# What this module provisions (minimal stage-1):
#   - Resource Group (Azure's namespace-per-deployment concept).
#   - Virtual Network + Subnet for AKS.
#   - AKS cluster with 1 × Standard_B2s node in the system pool.
#   - Log Analytics workspace for container logs.
#
# What's deferred (stage 2):
#   - Azure Database for PostgreSQL (bring-your-own via env var).
#   - Azure Cache for Redis (bring-your-own).
#   - Azure Event Hubs (Kafka-compatible API) — stage 2 migration path.
#   - Azure Application Gateway + WAF — stage 2.
#   - Workload Identity (Federated) — stage 2, mirrors GCP WIF.
#   - Azure AD RBAC for kubectl — stage 2, mirrors GKE IAM.
#
# Related:
#   - variables.tf            — inputs (location, resource group name, etc.)
#   - outputs.tf              — kubeconfig + cluster identifiers
#   - backend.tf              — local state (TODO: migrate to Azurite/Blob)
#   - README.md               — apply instructions + cost breakdown
#   - ADR-0036                — multi-cloud posture
#   - deploy/terraform/gcp/   — canonical target for comparison
# =============================================================================

# =============================================================================
# Role        : Terraform core + azurerm provider version pin.
# Why         : Same rationale as GCP/AWS — reproducible plans. The
#               azurerm 4.x series (GA 2024-08) moved many resources from
#               `features {}` block defaults to explicit arguments;
#               locking to `~> 4.0` is the modern default. 3.x is
#               legacy.
# Cost        : n/a (metadata only).
# Gotchas     : azurerm 4.x requires Terraform ≥ 1.5; 1.8 (repo baseline)
#               is safe. Bumping to `~> 5.0` when released WILL change
#               several resource defaults — read the upgrade guide.
# Related     : deploy/terraform/gcp/main.tf, deploy/terraform/aws/main.tf.
# =============================================================================
terraform {
  required_version = ">= 1.8"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }
}

# =============================================================================
# Role        : Azurerm provider config.
# Why         : The empty `features {}` block is MANDATORY in azurerm —
#               not declaring it throws a cryptic "features block
#               required" error. Feature flags inside toggle
#               soft-delete/purge behaviours per resource; defaults are
#               safe for a reference module.
#               Subscription + tenant credentials come from environment
#               variables (ARM_SUBSCRIPTION_ID, ARM_CLIENT_ID, etc.) or
#               `az login`, NOT from this file.
# Cost        : n/a (metadata only).
# Gotchas     : - Providing `subscription_id` inline would pin the
#                 module to one account. Omitted on purpose so `az
#                 account set --subscription X` drives the choice.
#               - The `features {}` block is evaluated early; adding
#                 flags later may force in-place resource updates.
# Related     : https://registry.terraform.io/providers/hashicorp/azurerm/latest/docs
# =============================================================================
provider "azurerm" {
  features {}
}

# =============================================================================
# Role        : Resource Group — the Azure "container" for every child
#               resource (networking, cluster, logs).
# Why         : Azure requires every resource to live in a Resource Group.
#               Creating one dedicated to mirador makes cleanup trivial
#               (`az group delete` drops everything) and keeps RBAC
#               scoping tight. Location defaults to "westeurope"
#               (Netherlands) for EU data-sovereignty parity with the
#               GCP europe-west1 choice.
# Cost        : €0 (Resource Groups are free, they're just metadata).
# Gotchas     : - Deleting the group deletes EVERY resource in it
#                 asynchronously — 20-30 min for an AKS cluster. No
#                 confirmation prompt from `az group delete -y`.
#               - Renaming is NOT supported; destroy + recreate is the
#                 only path. Pick a stable name.
# Related     : All other resources use `resource_group_name = azurerm_resource_group.main.name`.
# =============================================================================
resource "azurerm_resource_group" "main" {
  name     = var.resource_group_name
  location = var.location

  tags = {
    project = "mirador"
    env     = "reference"
    managed = "terraform"
  }
}

# =============================================================================
# Role        : VNet + subnet dedicated to the AKS node pool.
# Why         : AKS supports "bring your own VNet" (this module) or
#               auto-created VNet. BYO is preferred for stage 2 when
#               peering to managed Postgres / Redis — having the VNet
#               under Terraform avoids a "adopt existing VNet" migration.
#               CIDR 10.10.0.0/16 keeps it distinct from typical default
#               10.0.0.0/8 networks so a future VPN doesn't conflict.
# Cost        : €0 (VNet and subnets are free in Azure).
# Gotchas     : - Azure CNI (the default on AKS) allocates one VNet IP
#                 per Pod — a /24 subnet = ~250 pods max. /22 is safer
#                 once HPA kicks in.
#               - AKS requires the subnet to allow outbound internet for
#                 image pull + API server reach. No custom route table
#                 yet — stage 2 adds a NAT Gateway.
# Related     : azurerm_kubernetes_cluster.main (vnet_subnet_id).
# =============================================================================
resource "azurerm_virtual_network" "main" {
  name                = "${var.cluster_name}-vnet"
  address_space       = ["10.10.0.0/16"]
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name

  tags = { project = "mirador" }
}

resource "azurerm_subnet" "aks" {
  name                 = "${var.cluster_name}-subnet"
  resource_group_name  = azurerm_resource_group.main.name
  virtual_network_name = azurerm_virtual_network.main.name
  address_prefixes     = ["10.10.1.0/24"]
}

# =============================================================================
# Role        : Log Analytics workspace for AKS container insights + logs.
# Why         : AKS can ship container logs to Log Analytics via the
#               OMS agent. Cheaper than enabling Azure Monitor across
#               all resources. PerGB2018 pricing tier = pay-per-ingest,
#               ~€2/GB after the first 5 GB/month free.
#               retention_in_days = 30 matches AWS CloudWatch's default
#               30-day tier; for a reference module with zero traffic
#               this stays under the free quota.
# Cost        : ~€0 for a low-traffic reference deployment (first 5 GB
#               ingestion/month free). Prod-grade bumps to ~€30/month.
# Gotchas     : - Log Analytics workspace names are globally unique per
#                 region — append the resource group if you hit a clash.
#               - Per-GB pricing can surprise — a misconfigured verbose
#                 log can burn €100/month easily. Keep log level at INFO.
# Related     : azurerm_kubernetes_cluster.main.oms_agent (stage 2).
# =============================================================================
resource "azurerm_log_analytics_workspace" "main" {
  name                = "${var.cluster_name}-logs"
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  sku                 = "PerGB2018"
  retention_in_days   = 30

  tags = { project = "mirador" }
}

# =============================================================================
# Role        : AKS cluster — single small node pool for the demo.
# Why         : - Control plane is FREE on AKS (no EKS-style €72/month).
#               - 1 × Standard_B2s node (2 vCPU, 4 GB RAM): cheapest
#                 always-on option at ~€30/month. Burstable B-series
#                 tolerates spiky JVM startup without breaking the bank.
#               - `default_node_pool` is the "system" pool. For
#                 production you'd add a second user pool; reference
#                 module keeps one.
#               - `identity { type = "SystemAssigned" }` lets AKS manage
#                 its own managed identity for Azure API calls (pulling
#                 container images from ACR, reading the Log Analytics
#                 workspace). Simpler than a UserAssigned MI.
#               - `network_plugin = "azure"` (Azure CNI, default). Kubenet
#                 is lighter but being phased out; azure is the forward
#                 choice.
# Cost        : Control plane: €0. Single Standard_B2s node: ~€30/month
#               always-on. Ephemeral pattern (up only during demos)
#               brings this to <€1/month.
# Gotchas     : - Changing `default_node_pool.vm_size` requires pool
#                 replacement (destroy + recreate of the node pool),
#                 which triggers a brief outage. Scale via extra pools
#                 instead.
#               - AKS minor version (`kubernetes_version`) is not pinned
#                 here — AKS picks the latest supported. Stage 2 pins an
#                 explicit version for repeatable plans.
#               - Azure disk quota on free-tier accounts is 4 disks total.
#                 Each AKS node uses 2 disks (OS + kubelet); running
#                 concurrent AKS clusters on free-tier breaks this.
#               - `role_based_access_control_enabled = true` is
#                 non-negotiable on production; default = true in
#                 azurerm 4.x.
# Related     : ADR-0030 (why GCP not Azure as primary), ADR-0022
#               (ephemeral pattern equivalence).
# =============================================================================
resource "azurerm_kubernetes_cluster" "main" {
  name                = var.cluster_name
  location            = azurerm_resource_group.main.location
  resource_group_name = azurerm_resource_group.main.name
  dns_prefix          = var.cluster_name

  # DO NOT enable `private_cluster_enabled` here — a public control plane
  # keeps `kubectl` reachable from CI runners without a jumpbox. Flip
  # to private in stage 2 once a bastion is wired.
  # private_cluster_enabled = false

  default_node_pool {
    name           = "system"
    node_count     = var.node_count
    vm_size        = var.node_vm_size
    vnet_subnet_id = azurerm_subnet.aks.id

    # Disk sizing — 30 GB minimum on most VM sizes; default is fine for
    # the demo. Larger images or heavier workloads might want 128 GB.
    os_disk_size_gb = 30

    # Allow pod bursting: setting a higher max_pods than the Azure CNI
    # default lets HPA scale up without adding nodes too aggressively.
    max_pods = 30

    tags = { project = "mirador" }
  }

  # System-assigned managed identity — Azure creates it with the
  # cluster. Stage 2 switches to UserAssigned + Workload Identity
  # federation for the zero-long-lived-keys story.
  identity {
    type = "SystemAssigned"
  }

  network_profile {
    network_plugin = "azure"
    # dns_service_ip and service_cidr left as defaults (10.0.0.10 and
    # 10.0.0.0/16) — don't overlap with the VNet 10.10.0.0/16 above.
  }

  # Ship node + container logs to the Log Analytics workspace.
  oms_agent {
    log_analytics_workspace_id = azurerm_log_analytics_workspace.main.id
  }

  tags = { project = "mirador" }
}
