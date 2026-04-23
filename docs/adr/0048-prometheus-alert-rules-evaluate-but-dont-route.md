# ADR-0048: Mirador alert rules evaluate in Prometheus but don't route via Alertmanager

- **Status**: Amended 2026-04-23 — Alertmanager flipped ON with a null-receiver to demo the routing pipeline end-to-end. See § Amendment 2026-04-23 at bottom. Original intent (no external paging receiver) stands for production posture.
- **Date**: 2026-04-21 (amended 2026-04-23)
- **Deciders**: Mirador maintainers
- **Related**: [ADR-0014](0014-single-replica-for-demo.md) (single-replica demo), [ADR-0039](0039-two-observability-deployment-modes.md) (two observability modes)

## Context

Phase 3 O2 introduces [`PrometheusRule`](../../deploy/kubernetes/base/observability-prom/mirador-alerts.yaml)
resources with 7 alert rules (HTTP RED, latency, JVM resource pressure,
Kafka lag) plus supporting recording rules. The natural next step in a
production deployment would be enabling Alertmanager + configuring
routing (severity → receiver: page for `critical`, Slack for `warning`)
so fired alerts reach a human.

Two constraints shape the decision:

1. **ADR-0014 deliberately kept Alertmanager OFF** in both prom-enabled
   overlays (`local-prom`, `gke-prom`). The demo is single-replica and
   carries no 24/7 on-call rotation — routing alerts out of the cluster
   would require a receiver (Slack webhook, PagerDuty key, email SMTP)
   that the project explicitly decided not to configure (ADR-0014
   "Revisit criteria" names this exact case).
2. **The alerts still have real value without routing.** Prometheus
   evaluates rules regardless of receiver presence; the Alerts tab in
   Prometheus UI shows fired state, and Grafana's Alerting view picks
   them up via the Prometheus datasource. For a demo setting, "did the
   alert fire?" reading a dashboard is enough — nobody is paged.

Re-enabling Alertmanager is tempting (it's a one-liner in
`values-kube-prom-stack.yaml`) but it forces follow-up work: receiver
config, secrets for webhook URLs, silence rules to avoid notification
spam during chaos demos, runbook references that make sense when
unattended. Out of scope for a portfolio demo.

## Decision

Ship the `PrometheusRule` resources (8 rules across 5 groups) in
`deploy/kubernetes/base/observability-prom/`. Keep Alertmanager
**disabled** in both prom-enabled overlays per ADR-0014.

Severity labels follow the kube-prometheus-stack convention so they're
useful the day Alertmanager flips on:

| Severity | Intent | Expected reader |
|---|---|---|
| `critical` | Page-worthy in production | On-call engineer, immediately |
| `warning` | Next-business-day triage | Team channel, batched review |
| `info` | Trend / awareness only | Dashboard only, no notification |

All rules carry `team: platform` + `service: mirador-backend` labels.
Annotations include `summary` (pager-line), `description` (incident
triage body), and `runbook_url` pointing at a per-alert runbook stub
under `docs/ops/runbooks/`.

Naming convention: `Mirador<SubjectVerb>` e.g. `MiradorBackendDown`,
`MiradorHighErrorRate`. The `Mirador` prefix disambiguates from
kube-prometheus-stack's bundled rules (defaultRules is off but future
upgrades might re-enable selectively).

**Supporting artefacts shipped in the same phase:**

- 6 runbook stubs in [`docs/ops/runbooks/`](../ops/runbooks/) —
  `backend-down.md`, `high-error-rate.md`, `latency.md`,
  `heap-pressure.md`, `thread-contention.md`, `kafka-lag.md`.
  All follow the existing 5-heading template (quick triage, likely root
  causes, commands, fix that worked last time, when to escalate).
- CI integration: `promtool check rules` runs in the `validate` stage
  to catch PromQL syntax errors + label-selector typos before they
  reach cluster.

## Consequences

### Positive

- Alert logic lives under `git`, tested in CI (`promtool`), previewable
  in Prometheus UI under "Alerts". Zero on-call burden, zero receiver
  config to maintain.
- `runbook_url` annotations are clickable in Prometheus UI even without
  Alertmanager — operators land on a ready-to-use triage doc.
- Severity labels + `release: prometheus-stack` label selector make the
  future Alertmanager flip trivial: set `alertmanager.enabled: true` +
  configure one receiver with a route matching `severity=~"critical|warning"`.
- Recording rules give dashboards + alerts a single shared source of
  truth (e.g. `mirador:http_error_ratio:5m`) — no PromQL copy-paste
  drift between panels and alert expressions.

### Negative

- Alerts **don't notify anyone**. A silent failure can fire
  `MiradorBackendDown` for hours with no human noticing. Acceptable
  only in a demo — explicitly called out in every runbook's "When to
  escalate" section.
- Severity labels don't carry meaning today; they're aspirational.
  Reviewers might read `critical` and assume a paging pipeline exists.
  Mitigated by this ADR + the absence of any Alertmanager resource
  in the cluster (visible via `kubectl get alertmanager -A`).

### Neutral

- The rules load only in overlays that deploy kube-prometheus-stack
  (the operator provides the CRD). `local/` overlay (lgtm-only,
  ADR-0038) ignores the base/observability-prom/ kustomization — it
  never references it.

## Alternatives considered

### Alternative A — Enable Alertmanager with a local-only receiver (stdout / file)

Turn `alertmanager.enabled: true` and route all alerts to a dummy
receiver that prints to stdout. Pro: readers see "notifications work".
Con: adds ~300 MB pod + a real resource footprint on kind just for a
demo visual; the Alertmanager UI would show "firing → notified" with
no meaningful destination. Not worth the overhead.

### Alternative B — Skip PrometheusRule entirely, encode alerts as Grafana alert rules

Grafana 10+ supports alert rules defined directly in Grafana's UI /
provisioning YAML. Pro: zero dependency on Prometheus Operator. Con:
alert definitions live in Grafana's database, not as first-class
Kubernetes resources — harder to version, harder to diff in MRs.
PrometheusRule gives the same evaluation with better GitOps ergonomics.

### Alternative C — Put alerts in `base/` without conditional overlay inclusion

Simpler resources list in overlays. Con: breaks `local/` overlay
(lgtm-only, no operator, no CRD) with "no matches for kind
PrometheusRule". Current arrangement (dedicated `base/observability-prom/`
subdir, only referenced from prom overlays) avoids that trap.

## References

- `deploy/kubernetes/base/observability-prom/mirador-alerts.yaml` — the rules
- `docs/ops/runbooks/` — 6 alert-specific runbooks
- [ADR-0014](0014-single-replica-for-demo.md) — originating no-Alertmanager decision
- [ADR-0039](0039-two-observability-deployment-modes.md) — overlay split (local / local-prom / gke-prom)
- [kube-prometheus-stack defaults](https://github.com/prometheus-community/helm-charts/blob/main/charts/kube-prometheus-stack/values.yaml) — severity label convention + ruleSelector behaviour

---

## Amendment 2026-04-23 — Alertmanager ON with null-receiver (demo-tier)

**Change** : `alertmanager.enabled: true` in both `gke-prom/` and `local-prom/` overlays, with a minimal `config` block that routes every firing alert to a single `'null'` receiver (accepts the payload, does nothing — equivalent of `/dev/null`).

**Why** : demonstrate the full alert pipeline end-to-end (Prometheus fires → Alertmanager groups by `{alertname, namespace}` → emits on `/api/v2/alerts` for the UI to surface). Before the amendment, alerts fired in Prometheus UI but Alertmanager was off so there was no proof the project understood Alertmanager integration — a gap for portfolio review.

**Why still "don't route" in intent** : the null receiver is LITERALLY a non-delivery sink. No Slack, no email, no PagerDuty, no pager. The original ADR position (no external receiver until a real SLO + on-call rotation exists) is preserved in practice ; what changed is the demonstration of the wiring itself.

**Cost** : Alertmanager pod = +~50m CPU / ~50 MiB RAM (minimal). Negligible on both the €0.26/h GKE ephemeral cluster (ADR-0022) and the local-prom kind cluster.

**Swapping to a real receiver** (Slack webhook, PagerDuty, email) : comment block in `values-kube-prom-stack.yaml` has the exact replacement — change the `receivers:` entry from `- name: 'null'` to e.g. `- name: 'slack-demo'` + `slack_configs: [...]`. 5-minute change, zero ADR implications.

**Revisit** : still gated on "project gains real users + SLO + on-call". The null-receiver demo stays until that threshold. When it's time for real delivery, file ADR-00XX "Alertmanager receiver configuration" with the chosen integration.
