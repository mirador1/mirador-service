# =============================================================================
# Terraform remote state — LOCAL backend (stage 1 only).
#
# This module is a reference implementation that has never been applied
# against a billing account (ADR-0036). Local state is fine for a
# never-applied module because there is no state to protect yet.
#
# When this module gets applied for real, migrate to Scaleway Object
# Storage (S3-compatible):
#
#   terraform {
#     backend "s3" {
#       bucket                      = "mirador-tf-state"
#       key                         = "mirador/scaleway.tfstate"
#       region                      = "fr-par"
#       endpoints = {
#         s3 = "https://s3.fr-par.scw.cloud"
#       }
#       skip_credentials_validation = true
#       skip_region_validation      = true
#       skip_requesting_account_id  = true
#       skip_metadata_api_check     = true
#       use_path_style              = false
#     }
#   }
#
# Prerequisites for Scaleway Object Storage remote state (one-off):
#   scw object bucket create name=mirador-tf-state region=fr-par
#   # Then set AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY to the Scaleway
#   # object-storage keys (NOT your SCW_ACCESS_KEY — they're different
#   # credential pairs).
#
# Why S3 backend pointed at Scaleway, and not a dedicated Scaleway
# backend? Terraform does not ship a first-party `scaleway` backend.
# Scaleway Object Storage speaks S3; the S3 backend's custom endpoint
# support is the canonical bridge.
#
# Alternative for local dev: use `minio` (Docker) as a Scaleway Object
# Storage emulator to test TF modules against a real S3-compatible
# backend without touching billing.
#
# Migration path (when stage 2 lands):
#   terraform init -migrate-state
#
# TODO: flip to S3 backend against Scaleway Object Storage before first
# real apply.
# =============================================================================

# =============================================================================
# Role        : No remote backend yet — defaults to local `terraform.tfstate`.
# Why         : A never-applied module has no state to protect; adding
#               a remote backend forces an empty-bucket side-effect on
#               every contributor. When this becomes "applied for real",
#               migrate per the commented block above.
# Cost        : €0 (local state).
# Gotchas     : - Local state is lost if the machine dies. Fine for a
#                 reference module, NOT fine for anything applied.
#               - Scaleway Object Storage has NO native state locking
#                 (unlike AWS S3 + DynamoDB). A concurrent apply will
#                 NOT be blocked — coordinate out-of-band or use a
#                 Terraform Cloud run for real environments.
# Related     : deploy/terraform/gcp/backend.tf (canonical GCS backend).
# =============================================================================
terraform {
  # Intentionally empty: Terraform uses local backend by default.
  # Uncomment and fill the block above to migrate to Scaleway Object Storage.
}
