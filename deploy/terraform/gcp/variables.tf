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

variable "db_name" {
  description = "Cloud SQL database name"
  type        = string
  default     = "mirador"
}

variable "db_user" {
  description = "Cloud SQL application user"
  type        = string
  default     = "demo"
}

variable "db_password" {
  description = "Cloud SQL application user password"
  type        = string
  sensitive   = true
  # Set via TF_VAR_db_password env var or terraform.tfvars (never commit)
}

variable "db_tier" {
  description = "Cloud SQL machine tier. db-f1-micro (~$7/month) is fine for dev; db-g1-small for staging."
  type        = string
  default     = "db-f1-micro"
  # db-f1-micro  → ~$7/month  (shared vCPU, 0.6 GB RAM) — dev/demo
  # db-g1-small  → ~$25/month (shared vCPU, 1.7 GB RAM) — staging
  # db-n1-standard-1 → ~$50/month (1 dedicated vCPU) — production
}

variable "redis_tier" {
  description = "Memorystore Redis tier: BASIC (no replica) or STANDARD_HA (with replica)"
  type        = string
  default     = "BASIC"
  # BASIC      → ~$16/month (1 GB, no replica) — dev/staging
  # STANDARD_HA → ~$40/month (1 GB, 1 replica) — production
}

variable "redis_memory_size_gb" {
  description = "Memorystore Redis instance size in GB"
  type        = number
  default     = 1
}

variable "app_host" {
  description = "Public hostname for the application (used in Ingress and CORS). E.g. mirador.example.com"
  type        = string
}
