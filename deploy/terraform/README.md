# `deploy/terraform/` — Infrastructure-as-Code for production deployments

This directory holds the Terraform modules that provision the **cloud
infrastructure** the application runs on in production. Local development
stays Docker Compose–based (see `docker-compose.yml` at the repo root) — this
directory is only relevant when deploying to a managed cloud.

## Why Terraform at all?

- **Reproducibility**: the entire stack (VPC, GKE cluster, managed Postgres,
  managed Redis, IAM service accounts, firewall rules) is described as code.
  `terraform apply` recreates it identically in a new project.
- **Change review**: modifications go through `terraform plan` so the diff is
  visible before it hits the cloud — the same discipline as code review.
- **Tear-down**: `terraform destroy` cleanly removes everything provisioned
  through this code, avoiding forgotten resources that keep billing.
- **Day-2 operations**: IAM changes, disk resizing, network updates all live
  in the same place as the initial provisioning.

## Layout

| Sub-directory | Cloud provider | Status          | Purpose                                                                 |
| ------------- | -------------- | --------------- | ----------------------------------------------------------------------- |
| [`gcp/`](gcp/) | Google Cloud (GKE Autopilot, Cloud SQL, Memorystore) | Active — primary target | Full stack: VPC, GKE Autopilot cluster, Cloud SQL Postgres 17, Memorystore Redis, Workload Identity–bound service accounts, optional Managed Kafka. |

More cloud targets (AWS EKS, Azure AKS, bare-metal k3s) could be added as
sibling directories — but their Kubernetes manifests already live under
`deploy/kubernetes/` and are applied by the corresponding `deploy:*` CI jobs, so for now
Terraform is scoped to GCP only.

## How this is wired into the CI pipeline

The `.gitlab-ci.yml` defines two jobs in the `infra` stage:

1. **`terraform-plan`** — runs automatically on every `main` push and MR.
   Outputs a plan artifact that `terraform-apply` can consume. Marked
   `allow_failure: true` so it never blocks deployments of app code.
2. **`terraform-apply`** — manual trigger only (GitLab UI "▶ Play" on the
   job). Applies the plan produced by `terraform-plan`. Only runs on `main`.

Both use **Workload Identity Federation**: the GitLab CI job receives an OIDC
JWT, exchanges it via GCP STS for a short-lived access token bound to the
`gitlab-ci-deployer` service account. No service account key file is ever
committed or mounted.

State is stored in the GCS bucket referenced by the `TF_STATE_BUCKET` CI
variable (default: `project-8d6ea68c-33ac-412b-8aa-tf-state`). The bucket
must be created once manually before the first `terraform init` runs — see
`gcp/README.md`.

## Running Terraform locally (dev only)

```bash
brew install hashicorp/tap/terraform   # if not already installed

cd terraform/gcp
cp terraform.tfvars.example terraform.tfvars   # edit with real values
# terraform.tfvars is git-ignored — never commit credentials

terraform init \
  -backend-config="bucket=${GCP_PROJECT}-tf-state" \
  -backend-config="prefix=mirador/gcp"

terraform plan
terraform apply   # will prompt before making changes
```

Prerequisites locally:
```bash
gcloud auth application-default login
gcloud services enable container.googleapis.com sqladmin.googleapis.com \
  redis.googleapis.com servicenetworking.googleapis.com \
  serviceusage.googleapis.com --project=${GCP_PROJECT}
```

## When to touch this code

- **Adding a managed resource** (new Pub/Sub topic, new Cloud Storage
  bucket, etc.) — prefer Terraform over `gcloud` one-liners so the resource
  is reproducible.
- **Changing a tier** (e.g. Cloud SQL `db-f1-micro` → `db-g1-small`) — edit
  `terraform.tfvars` or `variables.tf` defaults, plan, apply.
- **Granting IAM** — add resources under a `resource "google_project_iam_*"`
  block in the appropriate file rather than running `gcloud projects
  add-iam-policy-binding` ad-hoc.

## What NOT to put here

- **Kubernetes manifests** → `deploy/kubernetes/` (Terraform doesn't manage workloads, only
  the cluster they run on).
- **Application secrets** → GitLab CI masked variables injected as K8s
  Secrets at deploy time.
- **Local development stack** → `docker-compose.yml`.

## Further reading

- `gcp/README.md` — detailed file-by-file walkthrough of the GCP module.
- `.gitlab-ci.yml` — the `.terraform-base`, `terraform-plan`, and
  `terraform-apply` job definitions.
- [Terraform GCS backend docs](https://developer.hashicorp.com/terraform/language/backend/gcs)
- [Workload Identity Federation for GitLab](https://docs.gitlab.com/ci/cloud_services/google_cloud/)
