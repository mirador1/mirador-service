# Runbooks — operational playbooks

When something looks wrong, check here first. Each runbook covers one
symptom, lists the 3–5 commands that usually pinpoint the cause, and
ends with the fix that worked the last time it was seen. The goal is
**3 minutes** from "something's off" to "I know what to do", not
"start re-reading the codebase".

Every runbook is self-contained — no prerequisite knowledge of other
runbooks, no implicit assumptions about the reader's recent activity.
Run them cold after a week away from the project.

| Symptom | Runbook |
|---|---|
| `./run.sh all` hangs or fails to bring up everything | [compose-startup-fails.md](compose-startup-fails.md) |
| GKE demo cluster won't provision or pods stay pending | [gke-cluster-boot-fails.md](gke-cluster-boot-fails.md) |
| Backend returns 503 under load | [backend-503.md](backend-503.md) |
| Kafka `/customers/{id}/enrich` times out | [kafka-enrich-timeout.md](kafka-enrich-timeout.md) |
| Ollama `/customers/{id}/bio` returns fallback "Bio temporarily unavailable." | [ollama-bio-fallback.md](ollama-bio-fallback.md) |
| CI pipeline auto-merge not firing | [auto-merge-stuck.md](auto-merge-stuck.md) |
| GCP bill higher than expected | see `docs/ops/cost-control.md` + `bin/budget/gcp-cost-audit.sh` |
| Playwright E2E fails in CI but passes locally | [e2e-ci-diff.md](e2e-ci-diff.md) *(pending — ROADMAP Tier-1 #2)* |

### Prometheus-alert runbooks (Phase 3 O2)

Referenced from the `runbook_url` annotation on each alert in
`deploy/kubernetes/base/observability-prom/mirador-alerts.yaml`. Fire order
below matches the alert group order.

| Alert | Runbook |
|---|---|
| `MiradorBackendDown` / `MiradorBackendAbsent` | [backend-down.md](backend-down.md) |
| `MiradorHighErrorRate` | [high-error-rate.md](high-error-rate.md) |
| `MiradorHighLatencyP95` | [latency.md](latency.md) |
| `MiradorHeapHigh` | [heap-pressure.md](heap-pressure.md) |
| `MiradorThreadContention` | [thread-contention.md](thread-contention.md) |
| `MiradorKafkaConsumerLagHigh` | [kafka-lag.md](kafka-lag.md) |

## Writing a new runbook

Keep them short. 5 headings, in this order:

```md
# <Symptom, one line>

## Quick triage (30 seconds)
## Likely root causes (in order of frequency)
## Commands to run
## Fix that worked last time
## When to escalate
```

"Escalate" means: involve the team, open an incident, rollback. For a
portfolio demo "escalate" usually reads "destroy the cluster and start
over" — write that explicitly when it's the right answer.
