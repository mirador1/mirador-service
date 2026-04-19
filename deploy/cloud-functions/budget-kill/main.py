"""
budget-kill — Cloud Function that destroys the GKE cluster when the
monthly budget hits 100% of its cap.

Triggered by Pub/Sub. The GCP billing budget (configured in
docs/ops/cost-control.md) is wired to publish a notification message to
the `mirador-budget-kill` topic on every threshold crossing. This
function subscribes, filters on the 100% threshold, and deletes the
ephemeral GKE cluster so the bill stops growing.

Design notes (why this, not something else):

- **Cluster delete, not pause.** GKE Autopilot has no pause mode. The
  cheapest way to stop paying is delete. State loss is acceptable —
  ADR-0022 already treats the cluster as ephemeral (recreate on
  demand via terraform). The tf-state bucket + GSM secrets survive.
- **Pub/Sub > Cloud Scheduler.** Cloud Scheduler polling the budget
  API would lag by up to the poll interval. Pub/Sub is event-driven
  and takes effect within ~6h of the actual spend crossing 100%
  (GCP budget sampling rate).
- **Filter on threshold AND costAmount.** Billing sends one message
  per threshold crossing (50%, 80%, 100%, 120%). We only kill on the
  100%+ thresholds; 50%/80% are informational emails.
- **Idempotent delete.** If the cluster is already gone (a previous
  threshold already triggered), `clusters delete` returns 404 and
  we exit 0. No retries, no spam.
- **Logs go to Cloud Logging by default.** The function runtime's
  `print()` output lands in Logs Explorer filtered by
  `resource.type=cloud_function` — searchable without extra wiring.
"""

import base64
import json
import os

from google.cloud import container_v1


PROJECT_ID = os.environ.get("GCP_PROJECT", "project-8d6ea68c-33ac-412b-8aa")
REGION = os.environ.get("GKE_REGION", "europe-west1")
CLUSTER = os.environ.get("GKE_CLUSTER", "mirador-prod")

# Kill threshold. 1.0 = 100% of budget. Anything below just logs.
KILL_THRESHOLD = float(os.environ.get("KILL_THRESHOLD", "1.0"))


def budget_kill(event, context):  # noqa: ARG001 — Cloud Functions signature
    """
    Cloud Function entrypoint (Pub/Sub trigger).

    `event["data"]` is a base64-encoded JSON payload from the Billing
    notification. Shape (as of 2026):
    {
      "budgetDisplayName": "Mirador €10 alert",
      "alertThresholdExceeded": 1.0,
      "costAmount": 10.47,
      "costIntervalStart": "2026-04-01T00:00:00Z",
      "budgetAmount": 10.0,
      "budgetAmountType": "SPECIFIED_AMOUNT",
      "currencyCode": "EUR"
    }

    We only act when `alertThresholdExceeded >= KILL_THRESHOLD`. Lower
    thresholds (50%, 80%) are logged but not actioned — they exist so
    the user gets an email warning before the hammer falls.
    """
    raw = base64.b64decode(event.get("data", "")).decode("utf-8")
    payload = json.loads(raw) if raw else {}

    threshold = float(payload.get("alertThresholdExceeded", 0))
    cost = payload.get("costAmount")
    cap = payload.get("budgetAmount")
    ccy = payload.get("currencyCode", "EUR")

    print(
        f"budget-kill received: threshold={threshold} "
        f"cost={cost}{ccy} cap={cap}{ccy}"
    )

    if threshold < KILL_THRESHOLD:
        # Under the kill line — the email alert is enough. We don't
        # want 50% crossings to nuke the cluster.
        print(f"  below kill threshold ({KILL_THRESHOLD}) — no action.")
        return

    # Delete the GKE cluster. Autopilot billing stops as soon as the
    # cluster is marked PROVISIONING→DELETING. Full removal takes a few
    # minutes but the billing line flatlines almost immediately.
    client = container_v1.ClusterManagerClient()
    name = f"projects/{PROJECT_ID}/locations/{REGION}/clusters/{CLUSTER}"

    try:
        op = client.delete_cluster(name=name)
        print(f"  cluster delete requested: op={op.name}")
    except Exception as e:  # noqa: BLE001 — log-and-swallow any error class
        # Most likely case: cluster already gone (previous threshold
        # already triggered). Don't fail the function or retries will
        # spam the logs.
        msg = str(e)
        if "NotFound" in msg or "404" in msg or "not found" in msg.lower():
            print(f"  cluster already gone — nothing to do.")
        else:
            print(f"  delete failed: {msg}")
            # Re-raise so Cloud Functions marks this invocation as
            # errored and the retry policy applies.
            raise
