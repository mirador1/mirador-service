# GKE cluster won't provision or pods stay pending

## Quick triage (30 seconds)

```bash
bin/dev/mirador-doctor                              # full health scan
gcloud container clusters list                  # is the cluster even there?
kubectl get pods -A --field-selector=status.phase!=Running
```

If `clusters list` is empty → `bin/cluster/demo/up.sh` hasn't run or failed.
If pods are Pending with "Insufficient cpu/memory" → Autopilot quota.

## Likely root causes (in order of frequency)

1. **Autopilot pod quota exceeded.** Autopilot auto-provisions nodes but
   within project-wide quotas. A cold demo with 15 pods can hit the
   default 32 vCPU quota if resource requests aren't tight.
2. **Terraform state lock.** A previous `bin/cluster/demo/up.sh` crashed
   leaving the GCS state lock held. `terraform apply` hangs.
3. **Budget-kill fired.** The Cloud Function `budget-kill` deleted
   the cluster because the monthly budget hit 100% (ADR-0022 + cost-
   control.md). Legit behaviour, not a bug.
4. **Image pull failure.** `ImagePullBackOff` on mirador:main — the
   Argo CD sync references a tag the registry doesn't have. Usually
   a CI push that didn't complete.
5. **Zone outage.** Rare, but real — check
   https://status.cloud.google.com/ for europe-west1.

## Commands to run

```bash
# 1. Cluster existence + status
gcloud container clusters describe mirador-prod --region=europe-west1 \
  --format="value(status,currentMasterVersion,currentNodeCount)"

# 2. Pending pods + events
kubectl get events -A --sort-by=.lastTimestamp | tail -30
kubectl describe pod -n app -l app=mirador | grep -A5 Events

# 3. Terraform state lock
gsutil ls gs://project-8d6ea68c-33ac-412b-8aa-tf-state/mirador/gcp/
gcloud storage objects list gs://project-8d6ea68c-33ac-412b-8aa-tf-state/mirador/gcp/ | grep lock

# 4. Cloud Function that might have killed the cluster
gcloud functions logs read budget-kill --region=europe-west1 --limit=20

# 5. Registry tags available
gcloud artifacts docker tags list \
  europe-west1-docker.pkg.dev/project-8d6ea68c-33ac-412b-8aa/mirador/mirador \
  --limit=5
```

## Fix that worked last time

- **Quota** — shrink the backend replica count to 1, skip Pyroscope,
  drop Chaos Mesh from the demo overlay. The overlay `overlays/gke-
  tight` already does this.
- **State lock** — delete the lock object (only if no other
  `terraform` is actually running):
  ```
  gcloud storage rm gs://.../mirador/gcp/default.tflock
  ```
- **budget-kill aftermath** — check `bin/budget/budget.sh status` and verify
  if the cap needs raising. If yes: `bin/budget/budget.sh set 20`. Then
  re-run `bin/cluster/demo/up.sh`.
- **Image pull** — check the last successful build SHA, push it
  through `glab ci` if needed, or temporarily pin the Argo CD
  Application's image tag to a known-good SHA via kustomize patch.

## When to escalate

For a portfolio demo, the escalation path is just **start over**.
The ephemeral pattern (ADR-0022) is designed for it:

```bash
bin/cluster/demo/down.sh     # idempotent, destroys whatever's there
bin/cluster/demo/up.sh       # recreate from scratch — 8 min cold start
```

If `bin/cluster/demo/up.sh` still fails with the same error after teardown,
the issue is in the Terraform config or the GKE release channel —
at that point, check https://status.cloud.google.com/ and consider
running the demo in another region.
