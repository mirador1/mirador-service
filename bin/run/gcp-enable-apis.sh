#!/usr/bin/env bash
# bin/run/gcp-enable-apis.sh — implements `./run.sh gcp-enable-apis`.
# Extracted 2026-04-22 from run.sh under Phase B-7-8 (1 sub-script per case).
# Invoked by the run.sh dispatcher; sources _preamble.sh for shared helpers.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$REPO_ROOT/bin/run/_preamble.sh"

    command -v gcloud >/dev/null 2>&1 || { echo "❌  gcloud not found — brew install google-cloud-sdk"; exit 1; }
    PROJECT=$(gcloud config get-value project 2>/dev/null)
    [ -z "$PROJECT" ] && { echo "❌  No GCP project set. Run: gcloud config set project <PROJECT_ID>"; exit 1; }
    echo "Enabling required GCP APIs for project: ${PROJECT}"
    gcloud services enable \
      container.googleapis.com \
      sqladmin.googleapis.com \
      redis.googleapis.com \
      servicenetworking.googleapis.com \
      managedkafka.googleapis.com \
      --project="${PROJECT}"
    echo "✅  APIs enabled."
