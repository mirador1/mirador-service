# Cost control — GCP billing alerts & cleanup

Operational reference for keeping the Mirador GCP bill under the
[ADR-0022](../adr/0022-ephemeral-cluster.md) €2/month target.

- **Budget alert** — tells you when you're drifting.
- **`bin/demo-down.sh`** — cleans at the source after every demo.
- **`bin/gcp-cost-audit.sh`** — safety net, catches what `demo-down`
  missed (crashed runs, out-of-band resource creation).

This page is the single source of truth for the three pieces. If one
drifts out of sync with the others, fix it here first.

## Budget alert — Mirador €10

A monthly budget on the GCP billing account with a soft alert at 50 %
and escalating thresholds up to 120 %. Created via `gcloud billing
budgets create` (April 2026). Documented below so it can be recreated
from scratch if the billing admin ever deletes it.

### Live configuration

| Field | Value |
|---|---|
| Billing account | `019384-EA1A6A-9D635C` |
| Budget ID | `cb08b055-d30e-4830-a18a-94bed797f116` |
| Display name | `Mirador €10 alert` |
| Amount | `10 EUR` per calendar month |
| Scope | Project `project-8d6ea68c-33ac-412b-8aa` only |
| Credits treatment | `INCLUDE_ALL_CREDITS` (counts the free-tier $300 credit as real spend — the alert fires based on gross cost, not net after credit) |
| Threshold 1 | `50 %` → €5 (heads-up) |
| Threshold 2 | `80 %` → €8 (drift clearly started) |
| Threshold 3 | `100 %` → €10 (target breached) |
| Threshold 4 | `120 %` → €12 (spike caught after the fact — see below) |
| Spend basis | `CURRENT_SPEND` on every threshold (actual dépense, not forecasted) |
| Notification | Default: email to billing-account admins (benoit.besson@gmail.com) |

### Why 4 thresholds and not just 100 %

GCP recomputes the actual spend every ~6 hours. If a resource starts
leaking money overnight (a forgotten VM, a runaway Cloud Run scale-up),
the 100 % threshold can fire **after** the bill has already jumped past
the target. The 120 % threshold is a **filet** — a second alert on the
way up that tells you the leak is still ongoing even after 100 % fired.

The 50 % / 80 % thresholds aren't noise: they're your **signal channel
before the bill is broken**. On a €2/month baseline, hitting €5 means
something is running that shouldn't be, with 25 days of runway to
decide what to do. That's a very different situation from "€10 hit
yesterday, no idea why".

### Recreate from scratch

If the budget is ever deleted (rotation of billing account, accidental
`budgets delete`, audit cleanup), reinstate it with:

```bash
gcloud services enable billingbudgets.googleapis.com

gcloud billing budgets create \
  --billing-account=019384-EA1A6A-9D635C \
  --display-name="Mirador €10 alert" \
  --budget-amount=10EUR \
  --threshold-rule=percent=0.5 \
  --threshold-rule=percent=0.8 \
  --threshold-rule=percent=1.0 \
  --threshold-rule=percent=1.2 \
  --filter-projects=projects/project-8d6ea68c-33ac-412b-8aa
```

No Terraform. This command is the source of truth — if we ever Terraform
it, the `google_billing_budget` resource must match these four
thresholds exactly. Any drift is a mistake, not a deliberate change.

### Tune the amount

When the monthly cap legitimately changes (e.g. real traffic starts),
update rather than delete + recreate so the history in the Billing
console stays consistent:

```bash
gcloud billing budgets update cb08b055-d30e-4830-a18a-94bed797f116 \
  --billing-account=019384-EA1A6A-9D635C \
  --budget-amount=20EUR
```

### Inspect current state

```bash
gcloud billing budgets describe cb08b055-d30e-4830-a18a-94bed797f116 \
  --billing-account=019384-EA1A6A-9D635C
```

## Prevention — demo-down.sh PVC purge

The root cause of most silent drift on this project is **orphaned
persistent disks**. `terraform destroy` tears down the GKE node pool
before any `kubectl delete pvc` runs; the disks end up parent-less,
still billed at €0.048/GB/month (PD-balanced, europe-west1).

`bin/demo-down.sh` now appends a cleanup pass after `terraform destroy`:

```bash
gcloud compute disks list \
  --filter="-users:* AND name:pvc-*" \
  --format="value(name,zone.basename())" \
  | while read name zone; do
      gcloud compute disks delete "$name" --zone="$zone" --quiet
    done
```

Filter rationale:

- `-users:*` — only disks with **no attached instance**. If a disk is
  still in use (attached to a Compute Engine instance for any reason),
  we never touch it. Belt-and-suspenders guard against deleting
  something stateful that isn't ours.
- `name:pvc-*` — only disks created by the GKE CSI driver, which always
  names them `pvc-<uuid>`. A manually-created disk named `backup-xyz`
  would not match. Narrower scope, safer blast radius.

## Safety net — `bin/gcp-cost-audit.sh`

If `demo-down.sh` crashes mid-flight (network glitch, aborted with ^C,
terraform state inconsistency), the PVC purge won't run. The standalone
audit script is the recovery path. Run it monthly via cron, or after
any "did we clean up?" moment:

```bash
bin/gcp-cost-audit.sh              # report only — safe
bin/gcp-cost-audit.sh --delete     # prompt-per-class deletion
bin/gcp-cost-audit.sh --yes        # non-interactive — CI / cron
```

The script scans **six** surfaces known to silently accumulate cost:

1. Orphaned PVC disks
2. Reserved static IPs not attached
3. Cloud NAT gateways
4. Load balancer forwarding rules
5. Persistent disk snapshots
6. GKE cluster currently up (Autopilot pods bill while running)

Each class comes with a €/month estimate (PD-balanced €0.048/GB,
static IP €1.50/month, NAT €1.20/month + egress, snapshots €0.025/GB).

### Cron (macOS launchd or GitLab scheduled pipeline)

```
0 2 1 * *  cd /path/to/mirador-service && bin/gcp-cost-audit.sh --yes
```

The first of each month, silent purge. If you prefer email reports,
redirect stdout to `mail -s "Mirador GCP audit" benoit.besson@gmail.com`.

## Auto-response — kill the cluster on 100 %

The budget alert doesn't just notify anymore: the 100 % threshold
triggers a Cloud Function that **deletes the GKE cluster**, stopping
the bill at source.

### Wire map

```
GCP Billing
  │ monthly spend sampling (~6 h cadence)
  ▼
Budget "Mirador €10 alert (auto-kill)"
  │ threshold crossed (50 / 80 / 100 / 120 %)
  │ publishes to Pub/Sub topic
  ▼
projects/<proj>/topics/mirador-budget-kill
  │ Pub/Sub delivery (at-least-once)
  ▼
Cloud Function "budget-kill" (2nd gen, Python 3.12)
  │ filters alertThresholdExceeded >= 1.0
  │ calls container.v1.ClusterManagerClient.delete_cluster
  ▼
GKE cluster mirador-prod → DELETING → gone in ~2 min
```

### Code

- Function: [`deploy/cloud-functions/budget-kill/main.py`](../../deploy/cloud-functions/budget-kill/main.py) (~60 LOC)
- Requirements: [`deploy/cloud-functions/budget-kill/requirements.txt`](../../deploy/cloud-functions/budget-kill/requirements.txt)
- Deploy: [`bin/budget-kill-deploy.sh`](../../bin/budget-kill-deploy.sh) — idempotent, re-run any time function code changes

### Filter rationale (why threshold check in code, not in Billing)

GCP Billing sends **one message per threshold crossing** (50 %, 80 %,
100 %, 120 %). There is no "only fire on 100 %" option in the budget
API — every threshold publishes. The filter lives in the Function so:

- 50 % / 80 % still produce their email warning via the default
  notification channel (unchanged behaviour)
- Only 100 %+ trigger the kill
- Adjustable via the `KILL_THRESHOLD` env var if the policy changes
  ("kill at 80 %" just means setting it to 0.8) without redeploying
  the budget

### IAM surface (what is allowed to delete the cluster)

One SA, one role, one project. Narrow on purpose.

| Principal | Role | Scope | Granted by |
|---|---|---|---|
| `<project-number>-compute@developer.gserviceaccount.com` (Cloud Functions default runtime SA) | `roles/container.admin` | Project | `bin/budget-kill-deploy.sh` step 4 |
| `billing-budget-notifications@system.gserviceaccount.com` (GCP-managed) | `roles/pubsub.publisher` | Topic `mirador-budget-kill` only | `bin/budget-kill-deploy.sh` step 5 |

Risks:

- **Function compromise = cluster delete.** Mitigated by the fact that
  the function source is read-only in the function runtime, the topic
  is project-scoped, and the cluster is ephemeral-by-design
  (ADR-0022) — "someone triggered a delete earlier than planned" is
  annoying, not catastrophic.
- **Budget misconfiguration = accidental kill.** Mitigated by the
  `KILL_THRESHOLD = 1.0` default and the requirement that the Billing
  payload explicitly contains `alertThresholdExceeded` — a malformed
  message goes through the "no action" branch.

### Test without actually deleting

Dry-run the full wire by publishing a fake 100 % message to the topic:

```bash
gcloud pubsub topics publish mirador-budget-kill \
  --message='{"budgetDisplayName":"Mirador €10 alert (auto-kill)","alertThresholdExceeded":1.0,"costAmount":10.47,"budgetAmount":10.0,"currencyCode":"EUR"}'
```

Then check the function log:

```bash
gcloud functions logs read budget-kill --region=europe-west1 --limit=20
```

If the cluster is already down (`demo-down.sh` ran), the log shows
`cluster already gone — nothing to do.` and nothing happens. If it's
up, it gets deleted within ~2 min.

### Rollback (disable the auto-destroy)

Two levels:

1. **Soft disable** — re-deploy the function with `KILL_THRESHOLD=2.0`.
   Threshold never reached, emails still fire, cluster untouched.
2. **Hard disable** — delete the function: `gcloud functions delete
   budget-kill --region=europe-west1 --quiet`. Pub/Sub messages still
   publish but have no subscriber, which is harmless.

## Related

- [ADR-0022 — ephemeral cluster pattern](../adr/0022-ephemeral-cluster.md)
- [ADR-0027 — declined service mesh (cost)](../adr/0027-decline-service-mesh-for-portfolio-demo.md)
- [`bin/demo-up.sh`](../../bin/demo-up.sh) / [`bin/demo-down.sh`](../../bin/demo-down.sh)
- [`bin/gcp-cost-audit.sh`](../../bin/gcp-cost-audit.sh)
