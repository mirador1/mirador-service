# `gke-prom` overlay — GKE Autopilot + kube-prometheus-stack alongside lgtm

The default `gke/` overlay deploys the lgtm single-pod stack with OTel
Collector receivers (kubeletstats + k8s_cluster). That gives Grafana
~90% of cluster-wide metrics at zero extra pod cost — see
[ADR-0038](../../../../docs/adr/0038-kubeletstats-receiver-in-lgtm-not-kube-prometheus-stack.md).

`gke-prom` is the alternative: layers the full
**kube-prometheus-stack** (Prometheus Operator, node-exporter,
kube-state-metrics) on top of the existing GKE stack so:

- **OpenLens** "Metrics" tab shows real CPU/memory graphs out of the
  box (the auto-detect script picks up the chart's Prometheus when the
  service `prometheus-stack-kube-prom-prometheus.monitoring` exists).
- Anything that hardcodes Prometheus-community / kube-state-metrics
  naming (`container_cpu_usage_seconds_total`, `kube_pod_status_phase`,
  community Grafana dashboards) gets the real source instead of the
  alias hack documented in ADR-0038.
- A **7-day retention window** persisted on a `standard-rwo` PVC means
  you can open Grafana the morning after a demo and see the full
  hour-by-hour timeline of what happened — not just the last 2 hours
  the local-prom emptyDir gives you.

See [ADR-0039](../../../../docs/adr/0039-two-observability-deployment-modes.md)
for the full decision narrative (the "GKE deployment" section covers
this overlay specifically).

## When to pick which GKE overlay

| Concern | `gke/` | `gke-prom/` |
|---|---|---|
| Pod count (Autopilot, ≥2 nodes) | ~10 | ~14-16 (1 node-exporter per node) |
| Steady-state RAM requested | ~1.5 GiB | ~2.4 GiB |
| Persistent storage | None (lgtm: emptyDir) | 10Gi PVC for Prometheus |
| OpenLens "Metrics" tab | Aliased OTel data (works since ADR-0038) | Native ksm/node-exporter data |
| Prometheus retention | N/A (Mimir = ~24 h on emptyDir) | **7 days** persisted |
| Grafana datasources | 4 (Mimir, Loki, Tempo, Pyroscope) | 5 (the 4 + kube-prom Prometheus) |
| OTLP push from app | lgtm Collector | lgtm Collector (unchanged) |
| Loki / Tempo / Pyroscope | lgtm | lgtm (unchanged) |
| Demo headline | "OTel-native, single pod" | "OTel-native + kube-prom hybrid" |
| Cost delta vs `gke/` | baseline | +€0.04/h compute + €0.40/month PVC |
| Default? | YES | No, opt-in for OpenLens / ksm work |

**`gke/` stays the default.** Pick `gke-prom/` when:

- Demoing OpenLens to someone who expects the real Prometheus.
- Building or evaluating a Grafana dashboard that targets
  ksm / node-exporter naming and doesn't care about lgtm's metric names.
- Debugging a Prometheus Operator integration before pushing it to
  production.
- The demo will run for more than 24 hours and you want metric history
  to survive lgtm pod restarts.

## Differences from local-prom

This overlay shares 90% of its YAML with `local-prom/` (same chart
version, same Operator + node-exporter + ksm + 6 ServiceMonitors). The
GKE-specific differences:

| Concern | `local-prom/` (kind) | `gke-prom/` (this) |
|---|---|---|
| Storage | emptyDir (lost on restart) | 10Gi `standard-rwo` PVC |
| Retention | 2 days | 7 days |
| Prometheus memory limit | 800Mi | 1500Mi |
| ImagePullPolicy patch | Yes (`Never` for kind-loaded images) | No (Artifact Registry by SHA) |
| Plain-Secret stand-ins | Yes (`local-secrets.yaml`) | No (ESO syncs from GSM) |
| ExternalSecret deletes | Yes (ESO has no GSM path on kind) | No (ESO works on GKE) |
| ConfigMap envsubst patch | No | Yes (`configmap-gke-patch.yaml`, mirrors gke/) |

Everything else (CRDs, rendered chart manifest, ServiceMonitor,
NetworkPolicy extras, Grafana datasource patch, mount patch) is
**byte-for-byte identical** to `local-prom/` — by intent, so a
reviewer can `diff -ur local-prom/ gke-prom/` and see only the
GKE-specific deltas.

## Apply

```bash
# Apply (server-side required because chart CRDs > 262 KiB)
kubectl apply --server-side --force-conflicts -k deploy/kubernetes/overlays/gke-prom

# Operator may need a restart if CRDs were created in the same apply
# (logs "resource X not installed" and never reconciles otherwise):
kubectl rollout restart deployment/prometheus-stack-kube-prom-operator -n monitoring

# Restart lgtm to pick up the new Grafana datasource:
kubectl rollout restart deployment/lgtm -n infra

# Then point OpenLens at the new Prometheus (script auto-detects
# kube-prom service and falls back to lgtm Mimir if absent):
bin/cluster/openlens-prometheus-config.sh
```

Verify cluster metrics:

```bash
# Port-forward kube-prom Prometheus
kubectl -n monitoring port-forward svc/prometheus-stack-kube-prom-prometheus 9090:9090 &

curl -s 'http://localhost:9090/api/v1/query?query=up{job=~"kubelet|kube-state-metrics|node-exporter"}' \
  | jq '.data.result | length'
# Should print 3 or more (one per Autopilot node for node-exporter).
```

Verify lgtm features still work:

```bash
# Loki — should return some app log lines
curl -s 'http://localhost:3100/loki/api/v1/query?query=%7Bservice_name%3D%22mirador%22%7D' | jq '.data.result | length'

# Tempo — list recent traces
curl -s 'http://localhost:3200/api/search?tags=service.name%3Dmirador' | jq '.traces | length'

# Pyroscope — list profiles
curl -s 'http://localhost:4040/api/apps' | jq 'length'
```

## Cost gotcha — PVC lifecycle vs cluster lifecycle

The cluster is ephemeral (ADR-0022 — `terraform destroy` on
`bin/cluster/demo-down.sh`). The 10Gi `standard-rwo` PVC backing
Prometheus is **not** destroyed by `terraform destroy` — GKE leaves
PVCs orphaned by default to avoid silent data loss.

Each orphan PVC bills **€0.40/month** (10Gi × €0.04/GiB) until manually
reclaimed. Two scrub paths:

```bash
# Manual: delete orphans before next bring-up
gcloud compute disks list --filter="name ~ '^pvc-'" --format='value(name,zone)' \
  | while read disk zone; do
      gcloud compute disks delete "$disk" --zone="$zone" --quiet
    done

# Automated: bin/budget/gcp-cost-audit.sh detects orphans + offers cleanup
bin/budget/gcp-cost-audit.sh           # dry-run inventory
bin/budget/gcp-cost-audit.sh --yes     # delete with confirmation
```

The audit script catches this drift weekly via the cron in
`bin/launchd/`. Don't apply this overlay if you'll forget the
cleanup — at €0.40 per orphan per month, ten missed cleanups across a
year burns half the project's annual budget.

## Regenerate the rendered chart

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update prometheus-community

helm template prometheus-stack \
  prometheus-community/kube-prometheus-stack \
  --version 83.6.0 \
  --namespace monitoring \
  --values deploy/kubernetes/overlays/gke-prom/values-kube-prom-stack.yaml \
  --include-crds \
  > /tmp/render.yaml

# Split into CRDs + everything else (CRDs are 95% of the line count,
# don't review them with the rest of the diff). Same recipe as
# local-prom — see deploy/kubernetes/overlays/local-prom/README.md
# "Regenerate the rendered chart" section for the Python splitter.
```

Bump the version: edit `values-kube-prom-stack.yaml` and the
`--version` flag above, regenerate, commit both files in one MR.
**Bump local-prom in the same MR** to keep the two overlays
diff-able — they share the chart version pin by design.

## Removal

```bash
kubectl delete -k deploy/kubernetes/overlays/gke-prom

# CRDs survive (kustomize is shy about CRD deletes). Drop them too:
kubectl delete crd alertmanagerconfigs.monitoring.coreos.com \
                  alertmanagers.monitoring.coreos.com \
                  podmonitors.monitoring.coreos.com \
                  probes.monitoring.coreos.com \
                  prometheusagents.monitoring.coreos.com \
                  prometheuses.monitoring.coreos.com \
                  prometheusrules.monitoring.coreos.com \
                  scrapeconfigs.monitoring.coreos.com \
                  servicemonitors.monitoring.coreos.com \
                  thanosrulers.monitoring.coreos.com

# Reclaim the orphaned Prometheus PVC (see "Cost gotcha" above):
kubectl delete pvc -n monitoring \
  prometheus-prometheus-stack-kube-prom-prometheus-db-prometheus-prometheus-stack-kube-prom-prometheus-0 || true

# Then re-apply the plain `gke/` overlay:
kubectl apply -k deploy/kubernetes/overlays/gke
```
