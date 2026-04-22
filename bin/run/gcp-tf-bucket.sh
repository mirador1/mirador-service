#!/usr/bin/env bash
# bin/run/gcp-tf-bucket.sh — implements `./run.sh gcp-tf-bucket`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    command -v gcloud >/dev/null 2>&1 || { echo "❌  gcloud not found — brew install google-cloud-sdk"; exit 1; }
    command -v gsutil >/dev/null 2>&1 || { echo "❌  gsutil not found — part of google-cloud-sdk"; exit 1; }
    PROJECT=$(gcloud config get-value project 2>/dev/null)
    [ -z "$PROJECT" ] && { echo "❌  No GCP project set."; exit 1; }
    REGION="${1:-europe-west1}"
    BUCKET="${PROJECT}-tf-state"
    echo "Creating Terraform state bucket: gs://${BUCKET} (region: ${REGION})"
    gsutil mb -p "${PROJECT}" -l "${REGION}" "gs://${BUCKET}" 2>/dev/null || echo "Bucket already exists."
    gsutil versioning set on "gs://${BUCKET}"
    echo "✅  GCS bucket ready: gs://${BUCKET}"
