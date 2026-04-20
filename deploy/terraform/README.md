# `deploy/terraform/` — Infrastructure-as-Code for production deployments

This directory holds the Terraform modules that provision the **cloud
infrastructure** the application runs on. Local development stays Docker
Compose–based (see `docker-compose.yml` at the repo root) — this directory
is only relevant when deploying to a managed cloud.

## Why Terraform at all?

- **Reproducibility**: the entire stack (VPC, cluster, managed database,
  managed cache, IAM service accounts, firewall rules) is described as
  code. `terraform apply` recreates it identically in a new project.
- **Change review**: modifications go through `terraform plan` so the
  diff is visible before it hits the cloud — the same discipline as
  code review.
- **Tear-down**: `terraform destroy` cleanly removes everything
  provisioned through this code, avoiding forgotten resources that
  keep billing.
- **Day-2 operations**: IAM changes, disk resizing, network updates
  all live in the same place as the initial provisioning.

## Cloud targets

Four cloud providers have Terraform modules. Only **GCP** is applied
in CI and covered by the demo; AWS / Azure / Scaleway are reference
implementations kept for portability review (see
[ADR-0036](../../docs/adr/0036-multi-cloud-terraform-posture.md)).

| Directory                     | Provider  | Stack                                    | Status                           | `terraform apply`? |
| ----------------------------- | --------- | ---------------------------------------- | -------------------------------- | ------------------ |
| [`gcp/`](gcp/)                | Google    | GKE Autopilot, default VPC               | **Canonical** — applied in CI    | Yes (CI)           |
| [`aws/`](aws/)                | Amazon    | ECS Fargate, default VPC, ALB            | Reference — stage 1              | Never              |
| [`azure/`](azure/)            | Microsoft | AKS (Standard_B2s), dedicated VNet       | Reference — stage 1              | Never              |
| [`scaleway/`](scaleway/)      | Scaleway  | Kapsule (Dev tier), DEV1-S node          | Reference — stage 1, EU-sovereign | Never              |

## When to pick which

**Default: use `gcp/`.** It's the only module applied in CI, tested in
demos, and cost-measured (not estimated). If you're deploying Mirador as
a demo and don't have a strong reason to pick differently, stay on GCP.

| If…                                                                       | Use        | Why                                                                                                    |
| ------------------------------------------------------------------------- | ---------- | ------------------------------------------------------------------------------------------------------ |
| You're running the reference demo or forking the project with no mandate. | `gcp/`     | Canonical, tested, €2/month ephemeral (ADR-0022). GKE Autopilot free control plane + per-pod billing. |
| Your org is on AWS and can't adopt a second cloud.                        | `aws/`     | ECS Fargate (no EKS control-plane fee). Loses Kubernetes-native features but keeps the cost story.     |
| Your org is on Azure.                                                     | `azure/`   | AKS has the same "free control plane" story as GKE. Closest to GCP semantically. Keeps K8s stack.      |
| You need EU data sovereignty (GDPR-only, no CLOUD Act exposure).          | `scaleway/`| French company (Iliad), Paris/Amsterdam/Warsaw regions. Cheapest of the four for single-node demos.    |
| You're prototyping and don't know which cloud yet.                        | `gcp/`     | Cheapest ephemeral option + the best docs. Switch later if a mandate appears.                          |

### Cost comparison (single-node demo, always-on vs ephemeral)

Ballpark costs in the default sizing of each module (EU regions):

| Provider | Cluster / control plane | Compute (1 node/task)    | Misc (LB / logs)   | Total 24/7 | Total ephemeral (~8h/month) |
| -------- | ----------------------- | ------------------------ | ------------------ | ---------- | --------------------------- |
| GCP      | €0 (first Autopilot)    | ~€190/mo (if left up)    | €0 (ingress-nginx) | ~€190      | **~€2**                     |
| AWS      | €0 (no EKS)             | ~€18/mo (0.5 vCPU / 1GB) | ~€16/mo (ALB)      | ~€34       | ~€0.40                      |
| Azure    | €0 (AKS control plane)  | ~€30/mo (Standard_B2s)   | €0 (no LB yet)     | ~€30       | <€1                         |
| Scaleway | €0 (Kapsule Dev)        | ~€10/mo (DEV1-S)         | €0 (no LB yet)     | **~€10**   | <€0.50                      |

Notes:

- GCP Autopilot's 24/7 cost is high (~€190/month), but the ephemeral
  pattern (ADR-0022) brings it to ~€2/month — cheapest across the
  four by an order of magnitude for realistic usage patterns.
- AWS numbers assume ECS Fargate **without** the EKS control-plane fee
  (€72/month). If the org mandates EKS, add €72 to every line.
- Azure + Scaleway are node-pool pricing (always-on); ephemeral patterns
  reduce linearly with node-up time.

### Feature matrix

| Feature                          | GCP             | AWS (Fargate)   | Azure (AKS)     | Scaleway (Kapsule) |
| -------------------------------- | --------------- | --------------- | --------------- | ------------------ |
| Free control plane               | Yes (Autopilot) | Yes (ECS, no CP)| Yes             | Yes (Dev tier)     |
| Per-pod billing                  | Yes (Autopilot) | Yes (Fargate)   | No (node pool)  | No (node pool)     |
| Workload Identity (OIDC, no keys) | Yes            | Yes (IRSA)      | Yes (Fed MI)    | No (API tokens)    |
| EU region                        | europe-west1    | eu-west-3       | westeurope      | fr-par / nl-ams    |
| Managed Postgres                 | Cloud SQL       | RDS             | Postgres FS     | Managed DB         |
| Managed Redis                    | Memorystore     | ElastiCache     | Cache for Redis | Managed Redis      |
| Managed Kafka                    | Managed Kafka   | MSK             | Event Hubs      | **Not available**  |
| GDPR-only jurisdiction           | No (CLOUD Act)  | No (CLOUD Act)  | No (CLOUD Act)  | **Yes**            |
| K8s CRDs / Argo CD / ESO         | Yes             | No (ECS)        | Yes             | Yes                |

## Files inside each cloud directory

All four modules follow the same layout (see each `README.md` for
file-by-file details):

| File                       | Role                                                                                  |
| -------------------------- | ------------------------------------------------------------------------------------- |
| `main.tf`                  | All resources: network, cluster, compute.                                             |
| `variables.tf`             | Inputs — region, cluster name, sizing knobs.                                          |
| `outputs.tf`               | Exported values — cluster credentials, URLs.                                          |
| `backend.tf`               | Remote state declaration. GCP uses GCS (wired); reference modules use local (with TODO). |
| `terraform.tfvars.example` | Template — `cp terraform.tfvars.example terraform.tfvars` then edit.                  |
| `README.md`                | Apply instructions, cost breakdown, stage-2 runbook.                                   |

## How the GCP module is wired into CI

The `.gitlab-ci.yml` defines two jobs in the `infra` stage:

1. **`terraform-plan`** — runs automatically on every `main` push and MR.
   Outputs a plan artifact that `terraform-apply` can consume. Marked
   `allow_failure: true` so it never blocks deployments of app code.
2. **`terraform-apply`** — manual trigger only (GitLab UI "▶ Play" on the
   job). Applies the plan produced by `terraform-plan`. Only runs on `main`.

Both use **Workload Identity Federation**: the GitLab CI job receives an
OIDC JWT, exchanges it via GCP STS for a short-lived access token bound
to the `gitlab-ci-deployer` service account. No service account key file
is ever committed or mounted.

State is stored in the GCS bucket referenced by the `TF_STATE_BUCKET` CI
variable (default: `project-8d6ea68c-33ac-412b-8aa-tf-state`). The bucket
must be created once manually before the first `terraform init` runs —
see [`gcp/README.md`](gcp/README.md).

The reference modules (AWS / Azure / Scaleway) have **no CI integration**
by design. They're scaffolded for review, not for deployment — see
ADR-0036.

## Running Terraform locally (dev only)

```bash
brew install hashicorp/tap/terraform   # if not already installed

cd deploy/terraform/gcp   # or aws/, azure/, scaleway/
cp terraform.tfvars.example terraform.tfvars   # edit with real values
# terraform.tfvars is git-ignored — never commit credentials

terraform init \
  -backend-config="bucket=${GCP_PROJECT}-tf-state" \
  -backend-config="prefix=mirador/gcp"
# (reference modules use local backend — no -backend-config needed)

terraform plan
terraform apply   # will prompt before making changes
```

Prerequisites locally vary per provider — see each module's README.

## When to touch this code

- **Adding a managed resource** (new Pub/Sub topic, new Cloud Storage
  bucket, etc.) — prefer Terraform over `gcloud` one-liners so the
  resource is reproducible.
- **Changing a tier** (e.g. Cloud SQL `db-f1-micro` → `db-g1-small`) —
  edit `terraform.tfvars` or `variables.tf` defaults, plan, apply.
- **Granting IAM** — add resources under a `resource "google_project_iam_*"`
  block in the appropriate file rather than running `gcloud projects
  add-iam-policy-binding` ad-hoc.

## What NOT to put here

- **Kubernetes manifests** → `deploy/kubernetes/` (Terraform doesn't
  manage workloads, only the cluster they run on).
- **Application secrets** → GitLab CI masked variables injected as K8s
  Secrets at deploy time.
- **Local development stack** → `docker-compose.yml`.

## Further reading

- [`gcp/README.md`](gcp/README.md) — detailed file-by-file walkthrough
  of the GCP module (canonical).
- [`aws/README.md`](aws/README.md) — AWS ECS Fargate reference.
- [`azure/README.md`](azure/README.md) — Azure AKS reference.
- [`scaleway/README.md`](scaleway/README.md) — Scaleway Kapsule reference
  (EU-sovereign).
- [ADR-0036](../../docs/adr/0036-multi-cloud-terraform-posture.md) —
  why one canonical + three reference modules.
- [ADR-0030](../../docs/adr/0030-choose-gcp-as-the-kubernetes-target.md) —
  why GCP is the canonical target.
- [ADR-0022](../../docs/adr/0022-ephemeral-demo-cluster.md) — the
  €2/month ephemeral pattern.
- `.gitlab-ci.yml` — the `.terraform-base`, `terraform-plan`, and
  `terraform-apply` job definitions.
- [Terraform GCS backend docs](https://developer.hashicorp.com/terraform/language/backend/gcs)
- [Workload Identity Federation for GitLab](https://docs.gitlab.com/ci/cloud_services/google_cloud/)
