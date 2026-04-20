# `terraform/scaleway/` — Scaleway Kapsule reference (EU-sovereign)

**Status: REFERENCE / STAGE 1** — never applied against a billing account.
Canonical target is GCP (see [`../gcp/`](../gcp/)). This module exists
to showcase an EU-sovereign alternative for teams that prioritise
data residency over breadth-of-services.

See [ADR-0036](../../../docs/adr/0036-multi-cloud-terraform-posture.md)
for the decision.

## Why Scaleway?

- **EU data sovereignty** — Scaleway S.A. is a French company owned by
  Iliad (€1.2B EU-HQ parent). Data stays under GDPR jurisdiction only;
  no CLOUD Act exposure like GCP/AWS/Azure (US-HQ hyperscalers).
- **Cheapest managed Kubernetes in Europe for small clusters.** Kapsule
  has a **free control plane** (Dev tier) AND node pricing from
  ~€10/month (DEV1-S). Roughly half the GCP/AKS always-on equivalent.
- **Native Paris + Amsterdam + Warsaw regions.** Latency from France
  ~2 ms, vs ~30 ms to GCP europe-west1 (Belgium).
- **Simpler API surface than AWS/Azure/GCP** — ~20 services total,
  easier to reason about the whole platform.

## Why not picked as the primary target?

- Smaller ecosystem (no managed Kafka; Object Storage is S3-compatible
  but with fewer features than real S3).
- No Workload Identity Federation equivalent — CI auth uses API tokens
  (rotated manually), not OIDC.
- No equivalent of GKE Autopilot / AKS Automatic (per-pod billing with
  zero node management).
- Documentation less mature than hyperscalers; some beta APIs move
  faster than Terraform provider releases.

## What gets provisioned

| Resource                          | Purpose                                                 | Approx. cost (fr-par, always-on) |
| --------------------------------- | ------------------------------------------------------- | -------------------------------- |
| `scaleway_k8s_cluster.main`       | Kapsule cluster, Dev tier, cilium CNI, K8s 1.31         | €0 (free control plane)          |
| `scaleway_k8s_pool.system`        | 1 × DEV1-S (2 vCPU / 2 GB) node                         | ~€10/mo                          |
| **Total always-on**               |                                                         | **~€10/mo**                      |
| **Total ephemeral (~8h/mo)**      |                                                         | **<€0.50/mo**                    |

This makes Scaleway the **cheapest** of the four targets for an
always-on single-node demo, by a factor of 2-3×.

## Files in this directory

| File                         | Role                                                                                               |
| ---------------------------- | -------------------------------------------------------------------------------------------------- |
| `main.tf`                    | Cluster + node pool.                                                                               |
| `variables.tf`               | Inputs: `region`, `cluster_name`, `kubernetes_version`, `node_type`, `node_count`, `app_host` (stage 2). |
| `outputs.tf`                 | `kapsule_cluster_id`, `kapsule_cluster_name`, `cluster_apiserver_url`, `kube_config` (sensitive), `region`. |
| `backend.tf`                 | Local backend only (stage 1). TODO in the file for Scaleway Object Storage (S3 backend) migration. |
| `terraform.tfvars.example`   | Template for local apply.                                                                          |
| `README.md`                  | This file.                                                                                         |

## Prerequisites (one-time, per project)

1. **Scaleway account + project** — https://console.scaleway.com/
2. **API key** — https://console.scaleway.com/iam/api-keys → "Create API key".
   Scope it to the target project, not organisation-wide.
3. **Export credentials** (the Terraform provider reads these):
   ```bash
   export SCW_ACCESS_KEY=...
   export SCW_SECRET_KEY=...
   export SCW_DEFAULT_ORGANIZATION_ID=...
   export SCW_DEFAULT_PROJECT_ID=...
   ```
4. **Install the `scw` CLI** (optional but useful for kubeconfig):
   ```bash
   brew install scw
   ```

## Usage

```bash
brew install hashicorp/tap/terraform   # if not already installed

cd deploy/terraform/scaleway
cp terraform.tfvars.example terraform.tfvars   # edit if defaults don't fit

terraform init      # local backend, no remote state bucket needed yet
terraform plan
terraform apply     # will prompt before making changes — ~5-7 min apply

# Get kubeconfig — two ways:
# (a) via scw CLI (recommended for interactive use)
scw k8s kubeconfig install "$(terraform output -raw kapsule_cluster_id)"

# (b) or write the raw kubeconfig from the output
terraform output -raw kube_config > ~/.kube/scaleway-config
export KUBECONFIG=~/.kube/scaleway-config
chmod 600 ~/.kube/scaleway-config

kubectl get nodes
```

## Tear down

```bash
terraform destroy
```

Kapsule destroys in ~5 min (node pool drain + cluster deprovision).
No deletion protection; destroy is unconditional.

## Known caveats (stage 1)

- **Single-AZ control plane** (Kapsule Dev tier). Zonal fault = API
  server offline. Kapsule Prod tier (€72/month) fixes this; deferred
  for cost reasons. Acceptable for a demo.
- **No managed Kafka** on Scaleway. Self-hosted in-cluster Kafka
  (per ADR-0005) works fine. Cross-cloud to Google Managed Kafka or
  AWS MSK is possible but defeats the EU-sovereignty goal.
- **No WIF equivalent** — CI auth uses long-lived API tokens. Rotate
  every 90 days manually via IAM.
- **No VPC / private networking** on Dev tier. Nodes get public IPs
  by default. Kapsule Prod + Private Networking (€10/month for VPC)
  fixes this.
- **No persistent database** — bring your own. Scaleway Managed DB
  for PostgreSQL starts at €16/month (Start tier, shared-CPU).
- **Local Terraform state** — see `backend.tf` for Scaleway Object
  Storage migration path. Note: no native state locking on
  Object Storage.

## What "stage 2" looks like

When this module graduates from reference to applied:

1. `backend.tf` → S3 backend pointed at Scaleway Object Storage.
2. `scaleway_rdb_instance` for Postgres 17 (Start tier, ~€16/month)
   + firewall rules.
3. `scaleway_redis_cluster` for Redis (~€10/month Tiny_Mesh).
4. Upgrade cluster to Kapsule Prod tier for multi-AZ control plane
   (€72/month) + `scaleway_vpc_private_network` for private networking.
5. Application Load Balancer (`scaleway_lb`) + ACM cert via Let's
   Encrypt (cert-manager in-cluster).
6. `scaleway_object_bucket` for artifact storage if cross-region
   replication is needed.
7. Rotate API keys every 90 days via an IAM schedule (manual; Scaleway
   has no automatic rotation).

## Related

- [`../gcp/`](../gcp/) — canonical target, full stack.
- [`../aws/`](../aws/) — ECS Fargate reference implementation.
- [`../azure/`](../azure/) — AKS reference implementation.
- [ADR-0036](../../../docs/adr/0036-multi-cloud-terraform-posture.md) —
  multi-cloud Terraform posture.
- [ADR-0030](../../../docs/adr/0030-choose-gcp-as-the-kubernetes-target.md) —
  why GCP is the primary target.
- [ADR-0022](../../../docs/adr/0022-ephemeral-demo-cluster.md) — ephemeral
  cluster pattern.
