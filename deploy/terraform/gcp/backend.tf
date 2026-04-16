# =============================================================================
# Terraform remote state — GCS bucket
#
# The bucket must exist before `terraform init` can use it.
# Create it once with:
#   gsutil mb -p ${project_id} -l ${region} gs://${project_id}-tf-state
#   gsutil versioning set on gs://${project_id}-tf-state
#
# Then run:
#   terraform init \
#     -backend-config="bucket=${project_id}-tf-state" \
#     -backend-config="prefix=mirador/gcp"
# =============================================================================

terraform {
  backend "gcs" {
    # bucket and prefix are injected via -backend-config at init time
    # to avoid hardcoding the project ID in committed files.
  }
}
