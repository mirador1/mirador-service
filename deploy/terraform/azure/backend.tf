# =============================================================================
# Terraform remote state — LOCAL backend (stage 1 only).
#
# This module is a reference implementation that has never been applied
# against a subscription (ADR-0036). Local state is fine for a
# never-applied module because there is no state to protect yet.
#
# When this module gets applied for real, migrate to Azure Blob Storage:
#
#   terraform {
#     backend "azurerm" {
#       resource_group_name  = "mirador-tfstate"
#       storage_account_name = "miradortfstate"
#       container_name       = "tfstate"
#       key                  = "mirador.azure.tfstate"
#       # Uses AAD auth by default — set ARM_USE_AZUREAD=true in CI.
#     }
#   }
#
# Prerequisites for remote state (chicken-and-egg, create manually once):
#   az group create --name mirador-tfstate --location westeurope
#   az storage account create \
#     --name miradortfstate \
#     --resource-group mirador-tfstate \
#     --location westeurope \
#     --sku Standard_LRS \
#     --encryption-services blob \
#     --min-tls-version TLS1_2
#   az storage container create \
#     --name tfstate \
#     --account-name miradortfstate
#
# Blob Storage in Azure gives us:
#   - Versioning (opt-in, add `--enable-versioning true` to the account).
#   - State locking via Azure Blob lease (built-in, no separate table
#     needed, unlike S3 + DynamoDB).
#   - Per-object RBAC via Storage Blob Data Contributor role.
#
# Alternative for local dev: run `Azurite` (Microsoft's local Azure
# Storage emulator, `docker run mcr.microsoft.com/azure-storage/azurite`)
# to avoid touching a real subscription while testing TF modules.
#
# Migration path (when stage 2 lands):
#   terraform init -migrate-state
#
# TODO: flip to azurerm backend before first real apply.
# =============================================================================

# =============================================================================
# Role        : No remote backend yet — defaults to local `terraform.tfstate`.
# Why         : A never-applied module has no state to protect; adding a
#               remote backend forces an empty-container side-effect on
#               every contributor. When this becomes "applied for real",
#               migrate per the commented block above.
# Cost        : €0 (local state).
# Gotchas     : - Local state is lost if the machine dies. Fine for a
#                 reference module, NOT fine for anything applied.
#               - Two developers running `terraform apply` simultaneously
#                 on local state will corrupt it. Azure Blob lease fixes
#                 this automatically.
# Related     : deploy/terraform/gcp/backend.tf (canonical GCS backend).
# =============================================================================
terraform {
  # Intentionally empty: Terraform uses local backend by default.
  # Uncomment and fill the block above to migrate to Azure Blob Storage.
}
