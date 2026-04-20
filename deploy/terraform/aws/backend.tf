# =============================================================================
# Terraform remote state — LOCAL backend (stage 1 only).
#
# This module is a reference implementation that has never been applied
# against a billing account (ADR-0036). Local state is fine for a
# never-applied module because there is no state to protect yet.
#
# When this module gets applied for real, migrate to remote state:
#
#   terraform {
#     backend "s3" {
#       bucket         = "mirador-tf-state"
#       key            = "aws/terraform.tfstate"
#       region         = "eu-west-3"
#       dynamodb_table = "mirador-tf-lock"   # required for state locking
#       encrypt        = true
#     }
#   }
#
# Prerequisites for S3 remote state (chicken-and-egg, create manually once):
#   aws s3api create-bucket --bucket mirador-tf-state \
#     --region eu-west-3 \
#     --create-bucket-configuration LocationConstraint=eu-west-3
#   aws s3api put-bucket-versioning --bucket mirador-tf-state \
#     --versioning-configuration Status=Enabled
#   aws s3api put-bucket-encryption --bucket mirador-tf-state \
#     --server-side-encryption-configuration '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
#   aws dynamodb create-table --table-name mirador-tf-lock \
#     --attribute-definitions AttributeName=LockID,AttributeType=S \
#     --key-schema AttributeName=LockID,KeyType=HASH \
#     --billing-mode PAY_PER_REQUEST \
#     --region eu-west-3
#
# Migration path (when stage 2 lands):
#   terraform init -migrate-state
#
# TODO: flip to S3 backend before first real apply.
# =============================================================================

# =============================================================================
# Role        : No remote backend yet — defaults to local `terraform.tfstate`.
# Why         : A never-applied module has no state to protect; adding a
#               remote backend forces an empty-bucket side-effect on
#               every contributor. When the module becomes "applied for
#               real", migrate per the commented block above.
# Cost        : €0 (local state).
# Gotchas     : - Local state is lost if the machine dies. Fine for a
#                 reference module, NOT fine for anything applied against
#                 a live account.
#               - Two developers running `terraform apply` simultaneously
#                 on local state will corrupt it. S3 + DynamoDB locking
#                 fixes this.
# Related     : deploy/terraform/gcp/backend.tf (canonical GCS backend for
#               reference on how the GCP side does it).
# =============================================================================
terraform {
  # Intentionally empty: Terraform uses local backend by default.
  # Uncomment and fill the block above to migrate to S3.
}
