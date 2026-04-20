# `terraform/azure/` — Azure reference implementation (AKS)

**Status: REFERENCE / STAGE 1** — never applied against a subscription.
Canonical target is GCP (see [`../gcp/`](../gcp/)). This module exists
so a reviewer or adopting team can see the project running on Azure
without rewriting from scratch.

See [ADR-0036](../../../docs/adr/0036-multi-cloud-terraform-posture.md)
for the decision.

## Why AKS, not Container Apps or App Service?

Three Azure options for running the image:

1. **AKS (Azure Kubernetes Service)** — full K8s. Control plane is
   **free** (like GKE, unlike EKS). You pay only for the node pool.
   Keeps the Kubernetes story intact (Argo CD, ESO, NetworkPolicy, HPA).
2. **Azure Container Apps** — serverless containers, per-vCPU-second.
   Simpler but loses K8s features. Equivalent of ECS Fargate on AWS.
3. **Azure App Service** — PaaS, JAR upload, not container-native.
   Too far from the K8s story the project showcases.

This module picks **AKS with a small node pool** (1× Standard_B2s).

**Why not AKS Automatic** (per-pod billing like GKE Autopilot)?
Newer API (2024-06 GA) with higher risk of change during the
portfolio's lifetime. The reference nature of this module doesn't
benefit from Automatic's "zero node config" value — we're showing
how to deploy, not how to run a hands-off production.

## What gets provisioned

| Resource                                 | Purpose                                                        | Approx. cost (westeurope, always-on)  |
| ---------------------------------------- | -------------------------------------------------------------- | ------------------------------------- |
| `azurerm_resource_group.main`            | Logical namespace for every Azure resource                     | €0                                    |
| `azurerm_virtual_network.main`           | VNet 10.10.0.0/16 dedicated to the cluster                     | €0                                    |
| `azurerm_subnet.aks`                     | Subnet 10.10.1.0/24 for AKS nodes                              | €0                                    |
| `azurerm_log_analytics_workspace.main`   | Container logs sink, 30-day retention                          | ~€0 under free 5 GB/month             |
| `azurerm_kubernetes_cluster.main`        | AKS control plane (free) + 1 × Standard_B2s system node        | ~€30/mo (node)                        |
| **Total always-on**                      |                                                                | **~€30/mo**                           |
| **Total ephemeral (~8h/mo)**             |                                                                | **<€1/mo**                            |

## Files in this directory

| File                         | Role                                                                                               |
| ---------------------------- | -------------------------------------------------------------------------------------------------- |
| `main.tf`                    | All resources above. Single-file module.                                                           |
| `variables.tf`               | Inputs: `location`, `resource_group_name`, `cluster_name`, `node_vm_size`, `node_count`, `app_host` (stage 2). |
| `outputs.tf`                 | `aks_cluster_name`, `resource_group_name`, `cluster_fqdn`, `kube_config` (sensitive), `log_analytics_workspace_id`, `location`. |
| `backend.tf`                 | Local backend only (stage 1). TODO in the file for azurerm/Blob migration.                         |
| `terraform.tfvars.example`   | Template for local apply.                                                                          |
| `README.md`                  | This file.                                                                                         |

## Prerequisites (one-time, per subscription)

1. **Credentials** — `az login` or OIDC WIF from CI (not wired here yet).
2. **Subscription selected** — `az account set --subscription <id>`.
3. **Resource providers registered** (usually automatic, but can fail on
   fresh subscriptions):
   ```bash
   az provider register --namespace Microsoft.ContainerService --wait
   az provider register --namespace Microsoft.OperationalInsights --wait
   az provider register --namespace Microsoft.Network --wait
   ```

## Usage

```bash
brew install hashicorp/tap/terraform   # if not already installed
brew install azure-cli

az login
az account set --subscription <your-subscription-id>

cd deploy/terraform/azure
cp terraform.tfvars.example terraform.tfvars   # edit if defaults don't fit

terraform init      # local backend, no remote state account needed yet
terraform plan
terraform apply     # will prompt before making changes — ~10 min apply

# Get kubeconfig
az aks get-credentials \
  --name "$(terraform output -raw aks_cluster_name)" \
  --resource-group "$(terraform output -raw resource_group_name)"
kubectl get nodes
```

## Tear down

```bash
terraform destroy
```

AKS takes ~15 min to fully destroy (node pool drain, control-plane
deprovision). Alternatively, `az group delete --name mirador-prod --yes
--no-wait` is faster but leaves Terraform state out of sync — use only
when you don't plan to `terraform apply` again.

## Known caveats (stage 1)

- **No Ingress / HTTPS** — no `kubectl apply` of an ingress controller.
  Stage 2 adds `ingress-nginx` or Application Gateway Ingress Controller
  + cert-manager + Let's Encrypt.
- **No persistent database** — bring your own via env vars (Postgres URL).
  Stage 2 provisions Azure Database for PostgreSQL (Flexible Server,
  ~€30/month for Burstable B1ms).
- **No Redis / Kafka** — bring-your-own. Azure Cache for Redis Standard C0
  is ~€13/month; Event Hubs for Kafka is per-throughput-unit.
- **Single node, no HPA headroom** — `node_count = 1`. Pod evictions during
  AKS maintenance = brief outage. Stage 2 sets `node_count = 3`.
- **System-assigned managed identity** — simpler but not suitable for
  Workload Identity federation. Stage 2 migrates to UserAssigned MI +
  `oidc_issuer_enabled = true` for zero-long-lived-keys parity with GCP
  WIF.
- **Public control plane** — `private_cluster_enabled = false`. Stage 2
  toggles private + bastion host for stronger threat model.
- **Local Terraform state** — see `backend.tf` for the Azure Blob migration
  path.

## What "stage 2" looks like

When this module graduates from reference to applied:

1. `backend.tf` → azurerm backend + Blob Storage account + lease locking.
2. `azurerm_postgresql_flexible_server` for Postgres + Firewall Rules.
3. `azurerm_redis_cache` for Redis (Standard C0).
4. `azurerm_eventhub_namespace` + kafka-enabled namespace for Event Hubs.
5. Workload Identity: `oidc_issuer_enabled = true`, UserAssigned MI,
   `azurerm_federated_identity_credential` per K8s SA.
6. `ingress-nginx` installed via `kubectl apply` (or Helm via `helm_release`
   if we wire the Helm provider).
7. cert-manager + Let's Encrypt HTTP-01 challenge for `var.app_host`.
8. `azurerm_log_analytics_solution` for Container Insights dashboards.

## Related

- [`../gcp/`](../gcp/) — canonical target, full stack.
- [`../aws/`](../aws/) — ECS Fargate reference implementation.
- [`../scaleway/`](../scaleway/) — Kapsule, EU-sovereign option.
- [ADR-0036](../../../docs/adr/0036-multi-cloud-terraform-posture.md) —
  multi-cloud Terraform posture.
- [ADR-0030](../../../docs/adr/0030-choose-gcp-as-the-kubernetes-target.md) —
  why GCP is the primary target (AKS was a close second).
- [ADR-0022](../../../docs/adr/0022-ephemeral-demo-cluster.md) — ephemeral
  cluster pattern.
