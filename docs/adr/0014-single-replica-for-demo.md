# ADR-0014: Single-replica deployments for the demo cluster

- **Status**: Accepted
- **Date**: 2026-04-18

## Context

The first `deploy:gke` pipelines shipped with `replicas: 2` on the
backend Deployment and default Argo CD / External Secrets manifests
(7 and 3 pods respectively, each requesting 500m CPU / 2 Gi memory —
Autopilot's unspecified-resources default). The cluster is a GKE
Autopilot with 2 small nodes (~2 and ~8 CPU) and tight region-level
quotas (SSD at 84 % of a 250 GB cap). New installs hit
`FailedScheduling: Insufficient cpu/memory` and `GCE quota exceeded`
when Autopilot tried to bin-pack.

The demo scenario does not require redundancy:
- There are no paying users whose traffic would be affected by a restart.
- Rollouts happen via `git revert` / new MR — blast radius is "one
  maintainer's browser tab while working on the cluster".
- Cost + queue scheduling matter more than HA.

## Decision

**Every workload in the demo cluster runs with `replicas: 1` and
explicit tight resource requests**, with architecture-level guarantees
that scaling up is a one-line change when the demo grows into real use:

| Layer | Replicas | Requests (CPU / mem) | Rationale |
|---|---|---|---|
| `app/mirador` backend | 1 | 250m / 512Mi | HPA wired to 0 min, 3 max when re-enabled |
| `argocd-*` | 1 (core only) | 50–100m / 128–256Mi | ApplicationSet/Dex/Notifications dropped |
| `external-secrets-*` | 1 | 50m / 128Mi | Single-tenant demo |
| `cert-manager-*` | 1 | 50m / 128Mi | LetsEncrypt-prod handles one cert |
| Infra (kafka/redis/postgres/keycloak) | 1 | default (already minimal) | Already at 1 since day one |

Argo CD's ApplicationSet controller, Dex auth, and Notifications
controller are removed from the install — the demo only uses a single
`Application` CR, no SSO, no webhook-driven alerts.

## Consequences

Positive:
- The 2 existing Autopilot nodes can host the full stack (≈3.5 CPU
  request total), no SSD quota bump needed.
- Cost floor stays predictable: ~€15/month for the 2 Autopilot nodes +
  ingress-nginx + LGTM — no surprise Cloud SQL bill, no HPA-driven scale
  during load-test bursts.
- Every Deployment/StatefulSet keeps its HPA-ready spec (labels,
  probes, topologySpreadConstraints) so bumping replicas is a one-line
  change — see the scaling playbook below.

Negative:
- A pod restart is a user-visible outage for however long Spring Boot
  takes to warm up (~30–60 s). Mitigated by the existing readiness probe
  cutover + Istio-style connection draining (only relevant if/when we
  re-enable multiple replicas).
- cert-manager leader election loses redundancy — acceptable because
  the demo has exactly one cert to renew every 90 days.

## Scaling playbook (for when the demo grows up)

1. **Backend**: `kubectl scale deployment mirador -n app --replicas=3`.
   Or better, un-skip the HPA in `base/hpa.yaml` by setting
   `spec.minReplicas: 2, maxReplicas: 5` — the Deployment is already
   stateless, wired to Micrometer metrics, with HPA v2 behaviour tuning.
2. **Argo CD**: re-apply the upstream manifest
   (`kubectl apply -n argocd -f .../argo-cd/stable/manifests/install.yaml`)
   and reset the resource requests removed by this ADR. Re-enable
   ApplicationSet when managing multiple environments.
3. **External Secrets**: scale the 3 deployments to 2 and set
   pod-anti-affinity across zones.
4. **Cert-manager**: bump replicas to 2 for leader election HA.

## References

- ADR-0005 — in-cluster Kafka for the demo (same "single-tenant demo"
  reasoning)
- ADR-0013 — in-cluster Postgres on GKE (same day, same scope reset)
