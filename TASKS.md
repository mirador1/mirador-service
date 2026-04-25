# TASKS — mirador-service-java

Open work only. Per `~/.claude/CLAUDE.md` rules : Java-only items
here ; done items removed (use `git tag -l` for history).

---

## 📊 SLO/SLA backlog (post Quick wins ADR-0058)

Quick wins SHIPPED 2026-04-25 : 3 SLOs as code (Sloth) + multi-burn-rate
alerting + Grafana SLO dashboard + ADR-0058 + sla.md. Below = next iterations.

- 🟢 **Dashboard "SLO breakdown by endpoint"** : current dashboard is
  service-wide. Add a panel sliced by `uri` to identify which endpoints
  contribute most to the budget burn. When SLO breaches happen — answers
  "which endpoint is dragging us down ?".

- 🟢 **Chaos-driven SLO demo** : wire `/customers/diagnostic/{slow-query,
  db-failure,kafka-timeout}` to intentionally burn budget for demo
  purposes. A "demo mode" Grafana annotation that overlays the burn rate
  timeseries with the chaos test markers. Sells the observability story
  in 30 seconds.

- 🟢 **Runbook section "What to do when SLO breached"** :
  `docs/runbooks/slo-availability.md`, `slo-latency.md`, `slo-enrichment.md`
  (URLs already referenced in `slo.yaml` annotations). Each : symptoms,
  first investigation steps, common root causes, escalation path,
  rollback procedure. Currently empty — links 404 on Alertmanager.

- 🟢 **Latency heatmap par endpoint** : Grafana panel using
  `http_server_requests_seconds_bucket` series, x=time × y=latency-bucket,
  color=request count. Shows tail-latency distribution in one glance —
  complement to p99 SLO compliance.

- 🟢 **Apdex score dashboard** : add `Apdex(0.5s, 2s)` calculation to the
  SLO dashboard. Apdex = (satisfied + tolerating/2) / total. Single number
  that captures "user satisfaction" — easier to communicate to non-SRE
  stakeholders than 3 separate SLOs.

- 🟢 **RTO/RPO measurement via chaos** : kill DB pod → measure
  time-to-recovery (RTO) + lost-transaction count (RPO). Chaos Mesh
  scenario already exists ; needs a results dashboard + pass/fail
  threshold based on the SLA's documented RTO/RPO.

- 🟢 **Monthly SLO review meeting cadence** : document in
  `docs/slo/review-cadence.md`. What to bring (compliance %, top burn
  contributors, capacity changes, deploy correlation), who attends,
  what's the output (tighten/relax SLO, error budget policy update).

## 🔧 Other

- 🟢 **README.fr.md sync** : Java README.md got a major rewrite
  2026-04-25 (badges trim + TL;DR + senior architect matrix + URL fixes).
  The French version still reflects the old structure — sync needed
  (lefthook readme-i18n-sync hook flagged it but bypassed for the
  initial push).

- 🟢 **GitHub mirror push verification** : after the README rewrite
  + URL fixes, verify the GitHub mirror at github.com/mirador1/mirador-
  service mirrors correctly (badge URLs, Wayback caching, anchor
  links).
