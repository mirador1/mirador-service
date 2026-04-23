# =============================================================================
# Terraform backend — local state for the OVH module (stage 1).
#
# Why local for now?
# ──────────────────
# Stage-2 will migrate to OVH Object Storage S3-compatible (Public Cloud
# Object Containers). For the first apply + iteration, local state is:
#
# - **Faster** to plan (no S3 round-trip, no state lock dance)
# - **Easier to debug** (the .tfstate file is right there)
# - **No additional resource** (Object Container needs to exist FIRST
#   before we can use it as a backend — chicken-and-egg if we try to
#   provision it via this same module)
#
# Migration path to remote state (stage-2):
#   1. Create an OVH Object Container manually:
#        ovhai object-storage container create \
#          --project <project_id> --region GRA9 --name mirador-tfstate
#   2. Generate S3 credentials (Public Cloud → Object Storage → S3 Users)
#   3. Replace the `backend "local" {}` block below with:
#        backend "s3" {
#          endpoint = "https://s3.gra9.io.cloud.ovh.net"
#          bucket   = "mirador-tfstate"
#          key      = "ovh/terraform.tfstate"
#          region   = "gra9"
#          access_key = "<from-step-2>"
#          secret_key = "<from-step-2>"
#          # OVH-specific S3 quirks:
#          skip_credentials_validation = true
#          skip_region_validation      = true
#          skip_metadata_api_check     = true
#          force_path_style            = true
#        }
#   4. `terraform init -migrate-state` (Terraform copies local → remote)
#
# Why GCP module went straight to GCS but OVH starts local?
# ─────────────────────────────────────────────────────────
# GCP module is older (created 2026-04-19); we knew the pattern. OVH is
# Day 1 — let's get apply/destroy working locally first, then add remote
# state in a separate MR once we're confident the module is stable.
# Locking down to local NOW would also make the first few iterations
# slower (fighting with state migration during the same MR that's
# trying to bring the module up).
#
# Tooling note (per ADR-0053): both `terraform init` and `tofu init`
# support the `local` backend identically. No dual-compat concern here.
# =============================================================================

terraform {
  backend "local" {
    # State file location: deploy/terraform/ovh/terraform.tfstate
    # (relative to the directory `terraform init` runs in).
    # .gitignore at repo root excludes *.tfstate already.
    path = "terraform.tfstate"
  }
}
