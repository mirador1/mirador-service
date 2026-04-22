#!/usr/bin/env bash
# bin/run/tf-apply.sh — implements `./run.sh tf-apply`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    command -v terraform >/dev/null 2>&1 || { echo "❌  terraform not found — brew install terraform"; exit 1; }
    cd deploy/terraform/gcp
    [ ! -f terraform.tfvars ] && { echo "❌  deploy/terraform/gcp/terraform.tfvars not found."; exit 1; }
    PROJECT=$(grep project_id terraform.tfvars | sed 's/.*= *"\(.*\)"/\1/')
    terraform init -backend-config="bucket=${PROJECT}-tf-state" -backend-config="prefix=mirador/gcp" -input=false
    terraform apply
