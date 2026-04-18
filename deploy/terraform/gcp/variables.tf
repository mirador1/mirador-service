# =============================================================================
# Terraform variables — GCP infrastructure for mirador
#
# Set values in terraform/gcp/terraform.tfvars (not committed to Git)
# or via TF_VAR_* environment variables in CI.
#
# Usage:
#   cd terraform/gcp
#   terraform init
#   terraform plan
#   terraform apply
# =============================================================================

variable "project_id" {
  description = "GCP project ID (e.g. my-project-123456)"
  type        = string
}

variable "region" {
  description = "GCP region for the GKE cluster and Cloud SQL instance"
  type        = string
  default     = "europe-west1"
}

variable "cluster_name" {
  description = "Name of the GKE Autopilot cluster"
  type        = string
  default     = "mirador-prod"
  # Matches the GKE_CLUSTER CI variable so deploy:gke can fetch credentials
  # with `gcloud container clusters get-credentials $GKE_CLUSTER` without
  # needing a separate TF_VAR_cluster_name override.
}

# db_*, redis_* variables removed with the Cloud SQL / Memorystore blocks
# (ADR-0013 + ADR-0021). Reactivation path in
# docs/archive/terraform-deferred/ keeps the previous declarations.

variable "app_host" {
  description = "Public hostname for the application (used in Ingress and CORS). E.g. mirador.example.com"
  type        = string
}
