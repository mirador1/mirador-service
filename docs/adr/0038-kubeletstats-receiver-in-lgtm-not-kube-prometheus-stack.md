# ADR-0038 — Cluster metrics via OTel Collector receivers in lgtm, not kube-prometheus-stack

- Status: Accepted
- Date: 2026-04-20
- Deciders: @benoit.besson
- Related: ADR-0014 (LGTM single-pod observability), ADR-0022 (ephemeral GKE cluster €10/month budget)

## Context

The `grafana/otel-lgtm` pod (1 container running Grafana + Loki + Tempo
+ Mimir + an OTel Collector) is wired for **OTLP push** from application
code. Mirador backend pods emit traces/logs/metrics via OTLP → Collector
fans them out to Loki/Tempo/Mimir.

This means Mimir (lgtm's embedded Prometheus backend) receives ONLY what
Mirador pushes — there's no scraping of kubelet or the K8s API. Two
concrete symptoms on 2026-04-20:

1. **OpenLens** "Metrics" tab displayed `Metrics are not available due
   to missing or invalid Prometheus configuration`. OpenLens queries
   Prometheus-compatible metrics like `container_cpu_usage_seconds_total`
   and `kube_pod_status_ready` — both kubelet/kube-state-metrics-sourced,
   neither present in our Mimir.
2. **HPA** errored `failed to get cpu utilization: ... the server could
   not find the requested resource (get pods.metrics.k8s.io)`. This is a
   separate issue (Kubernetes metrics API, addressed via metrics-server),
   but same root cause class: cluster-wide pod metrics missing.

## Decision

**Extend the existing lgtm OTel Collector with two additional receivers
instead of adding a full kube-prometheus-stack.** The two receivers are:

- **`kubeletstats`** — scrapes each node's kubelet `/stats/summary`
  endpoint every 30 s. Produces CPU/memory/network/filesystem metrics
  per container, pod, and node.
- **`k8s_cluster`** — watches the K8s API and emits metrics about
  resource state (pod phases, container restart counts, deployment
  ready replicas, node conditions, HPA status).

Both route into lgtm's existing `metrics` pipeline → Mimir. Grafana (in
the same pod) sees them as first-class Mimir/Prometheus metrics
alongside the OTLP-pushed app metrics.

Implementation: `deploy/kubernetes/base/observability/lgtm-k8s-receivers.yaml`

- ServiceAccount `lgtm-collector` with ClusterRole granting
  `nodes/stats`, `nodes/proxy`, and get/list/watch on most resources.
- ConfigMap `lgtm-otelcol-config` containing the full `otelcol-config.yaml`
  (upstream defaults + the 2 new receivers).
- `deploy/kubernetes/base/observability/lgtm.yaml` patched to:
  - Use the `lgtm-collector` ServiceAccount.
  - Mount the ConfigMap's `otelcol-config.yaml` via `subPath` to override
    `/otel-lgtm/otelcol-config.yaml` (preserves the rest of `/otel-lgtm/`).
  - Inject `K8S_NODE_NAME` env var (downward API, `spec.nodeName`) —
    used in the kubeletstats receiver endpoint template.

## Options considered

### Option A (chosen): OTel Collector receivers in lgtm — 0 extra pods

**Pros**
- Zero additional pods. lgtm stays 1 pod total.
- Reuses existing Mimir — no new TSDB, no extra disk.
- Grafana is already wired to Mimir as a datasource — new metrics
  appear with no config change.
- OpenLens points at `infra/lgtm:9009` for its Prometheus service and
  sees a fully-populated Prometheus-compat API.

**Cons / what we LOSE vs kube-prometheus-stack**
- No Alertmanager for routing → Grafana's alert rules (with webhook /
  Slack / email contact points) cover basic alerting but miss silencing,
  deduplication, and inhibition rules.
- No long-term storage (Mimir here uses emptyDir — data lost on lgtm
  pod restart). Acceptable for ephemeral demo cluster (ADR-0022).
- No HA (lgtm is 1 replica). Acceptable for demo.
- No dedicated `node-exporter` DaemonSet. `kubeletstats` covers node
  CPU/memory/network/fs but omits a few niche metrics like `node_load5`
  or per-filesystem mount points that node-exporter exposes.
- No `kube-state-metrics` daemon. `k8s_cluster` receiver covers the
  same resource-state telemetry but via the K8s API rather than kube-
  state-metrics' scrape target, so the metric names differ:
  `k8s.pod.phase` (OTel naming) vs `kube_pod_status_phase` (ksm naming).
  Dashboards referencing ksm names need updating.

### Option B (rejected): kube-prometheus-stack (Helm chart) — ~30 extra pods

- Prometheus server + Alertmanager + Grafana (duplicate of lgtm's) +
  node-exporter DaemonSet + kube-state-metrics + Prometheus Operator
  (CRDs) + multiple config-reloaders, admission webhooks, HA sidecars.
- ~400 MB RAM minimum, ~1-1.5 GB in steady state.
- For ADR-0022 ephemeral cluster budget: unaffordable overhead.
- For an "all-in-one demo" aesthetic: dilutes the lgtm single-pod story
  that's documented in ADR-0014.

### Option C (rejected): metrics-server only — lightweight but limited

- Ships only `pods.metrics.k8s.io` + `nodes.metrics.k8s.io` (now-only,
  no history).
- Fixes HPA and `kubectl top`.
- Does NOT fix OpenLens (it wants Prometheus, not K8s metrics API).
- Ships as a separate Deployment (1 extra pod).

We kept metrics-server installed because HPA relies on it specifically
(custom metrics adapter backed by Mimir is a separate migration). Both
live side-by-side — metrics-server for `.metrics.k8s.io`, OTel receivers
for `/api/v1/query`.

## Consequences

**Positive**
- Cluster-wide metrics available in Grafana + OpenLens with zero extra
  pods.
- Configuration is declarative YAML, git-tracked, applies via kustomize.
- Same pattern works on GKE (where kubelet certs are valid) by removing
  `insecure_skip_verify` from the kubeletstats receiver config.

**Negative**
- Metric names follow OTel semantic conventions (`k8s.pod.*`,
  `container.cpu.time`) instead of Prometheus-community ksm names
  (`kube_pod_*`, `container_cpu_usage_seconds_total`). Dashboards
  imported from Grafana.com that assume ksm naming need aliasing.
  Mitigation: lgtm Grafana already ships our custom dashboards; we
  author them against OTel names going forward.
- The single-pod lgtm is now also a critical-path component for cluster
  metrics. If lgtm OOMs, we lose not just app telemetry but cluster
  visibility. On ADR-0022 ephemeral cluster we accept this.

## OpenLens compatibility aliases (addendum 2026-04-20-2340)

After initial deploy, OpenLens still showed `Metrics are not available
due to missing or invalid Prometheus configuration`. Root cause: OpenLens
has hardcoded query names expecting Prometheus-community / kube-state-
metrics naming (`container_cpu_usage_seconds_total`, `kube_pod_status_phase`,
etc.). OTel semantic conventions emit different names
(`container.cpu.time`, `k8s.pod.phase`) — Mimir stores the data but
OpenLens queries the "wrong" names and sees empty series.

Fix: added a `metricstransform/openlens-aliases` processor in the
Collector pipeline that COPIES (action=insert) OTel-named metrics under
their ksm-compatible alias. Both names coexist in Mimir so:

- Our custom Grafana dashboards keep using OTel names (semantic-convention
  aligned, what the OTel ecosystem speaks).
- OpenLens sees the ksm-compatible names and lights up.
- No data duplication cost (metrictransform "insert" is a metadata copy,
  not a sample duplication).

Aliases today:

| OTel name                        | ksm alias                                      |
|----------------------------------|------------------------------------------------|
| `container.cpu.time`             | `container_cpu_usage_seconds_total`            |
| `container.memory.usage`         | `container_memory_working_set_bytes`           |
| `container.filesystem.usage`     | `container_fs_usage_bytes`                     |
| `k8s.pod.phase`                  | `kube_pod_status_phase`                        |
| `k8s.container.ready`            | `kube_pod_container_status_ready`              |
| `k8s.node.condition_ready`       | `kube_node_status_condition`                   |

Expand the list if OpenLens surfaces new "missing metric" errors. Grep
OpenLens source (`src/renderer/components/+cluster/cluster-metrics/`)
for the exact query strings when debugging.

## Revisit criteria

- Move to production-grade SLO (alerts > 10/min, multi-tenant isolation,
  audit retention > 7 d) → switch to kube-prometheus-stack.
- Need for historical metrics beyond lgtm pod lifetime → add a persistent
  volume to Mimir, OR switch to kube-prometheus-stack with Thanos/S3.
- Any dashboard outside our custom ones → evaluate metric name aliasing
  vs ksm install.

## References

- `deploy/kubernetes/base/observability/lgtm-k8s-receivers.yaml` —
  ServiceAccount + ClusterRole + ConfigMap.
- `deploy/kubernetes/base/observability/lgtm.yaml` — mount + env patches.
- OTel Collector [kubeletstats receiver docs](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/receiver/kubeletstatsreceiver)
- OTel Collector [k8s_cluster receiver docs](https://github.com/open-telemetry/opentelemetry-collector-contrib/tree/main/receiver/k8sclusterreceiver)
- ADR-0014 — LGTM single-pod observability stack (original decision).
- ADR-0022 — Ephemeral GKE cluster budget.
