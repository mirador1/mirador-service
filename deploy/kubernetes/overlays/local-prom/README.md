# `local-prom` overlay — kind + kube-prometheus-stack alongside lgtm

The default `local/` overlay deploys the lgtm single-pod stack with OTel
Collector receivers (kubeletstats + k8s_cluster). That gives Grafana
~90% of cluster-wide metrics at zero extra pod cost — see
[ADR-0038](../../../../docs/adr/0038-kubeletstats-receiver-in-lgtm-not-kube-prometheus-stack.md).

`local-prom` is the alternative: layers the full
**kube-prometheus-stack** (Prometheus Operator, node-exporter,
kube-state-metrics) on top of the existing local stack so:

- **OpenLens** "Metrics" tab shows real CPU/memory graphs out of the
  box (it autodetects the chart's Prometheus when the service name
  matches `prometheus-stack-kube-prom-prometheus`).
- Anything that hardcodes Prometheus-community / kube-state-metrics
  naming (`container_cpu_usage_seconds_total`, `kube_pod_status_phase`,
  community Grafana dashboards) gets the real source instead of the
  alias hack documented in ADR-0038.

See [ADR-0039](../../../../docs/adr/0039-two-observability-deployment-modes.md)
for the full decision narrative.

## When to pick which overlay

| Concern | `local/` | `local-prom/` |
|---|---|---|
| Pod count (kind, 1 node) | ~10 | ~14 |
| Steady-state RAM | ~1.5 GiB | ~1.7 GiB (measured: +226 MiB) |
| OpenLens "Metrics" tab | Aliased OTel data (works since ADR-0038) | Native ksm/node-exporter data |
| Grafana dashboards (Mirador's own) | Loki + Tempo + Mimir + Pyroscope | Same + 5th datasource (kube-prom Prometheus) |
| OTLP push from app | lgtm Collector | lgtm Collector (unchanged) |
| Grafana count | 1 (lgtm's) | 1 (lgtm's — chart's Grafana is disabled) |
| Prometheus count | 1 (lgtm's Mimir) | 2 (Mimir + chart's Prometheus, distinct UIDs) |
| Demo headline | "OTel-native, single pod" | "Both worlds, hybrid" |
| Footprint | Small | Medium |

**Default: `local/`.** Switch to `local-prom/` only when you actually
need the kube-prom Prometheus surface (OpenLens demo, ksm dashboards,
debugging operator integrations).

## Architecture chosen — Option B from the design discussion

The task spec considered three options:
- **A**: kube-prom-stack alongside lgtm with duplicated Grafana (~30 pods).
- **B**: kube-prom-stack alongside lgtm sharing lgtm's Grafana (~25 pods,
  no duplicate UI).
- **C**: replace lgtm with kube-prom-stack + separate Loki/Tempo/Pyroscope.

**Option B was chosen.** Reasoning:

- Single Grafana (lgtm's) → one URL, one dashboard library, no user
  confusion about "which Grafana".
- Both Prometheus instances are wired into the same Grafana via distinct
  datasource UIDs — queries pick whichever has the data they need.
- Loki (logs) + Tempo (traces) + Pyroscope (profiles) keep working
  because lgtm is unchanged. Zero regression on the OTLP path.
- Smaller footprint than A (no duplicate Grafana).
- Less destructive than C (lgtm pod, dashboards, Pyroscope wiring all
  preserved — `git checkout local-prom → local` is just `kubectl apply`).

## Pod inventory added on top of `local/`

| Component | Count | Measured RAM (idle) | Notes |
|---|---|---|---|
| Prometheus Operator | 1 | 24 MiB | Reconciles CRDs into StatefulSets. |
| Prometheus (server) | 1 | 174 MiB | 2-day retention, emptyDir, scrape every 30s. |
| node-exporter (DaemonSet) | 1 per node | 10 MiB | kind = 1 node = 1 pod. PSS=privileged required. |
| kube-state-metrics | 1 | 18 MiB | Cluster object state → Prometheus metrics. |
| Webhook patch (Job) | 2 (run-once) | tiny | Patches the chart's admission webhook caBundle, then `Completed`. |

Total: **4 extra long-lived pods, ~226 MiB RSS** measured at steady
state on kind. Worst-case (sum of resource _limits_): 1.25 GiB.

## Apply

```bash
# From repo root
kubectl apply -k deploy/kubernetes/overlays/local-prom

# Then point OpenLens at the new Prometheus (script auto-detects
# kube-prom service and falls back to lgtm Mimir if not present).
bin/cluster/openlens-prometheus-config.sh
```

Verify cluster metrics:

```bash
kubectl -n monitoring port-forward svc/prometheus-stack-kube-prom-prometheus 9090:9090 &
curl -s 'http://localhost:9090/api/v1/query?query=up{job=~"kubelet|kube-state-metrics|node-exporter"}' | jq '.data.result | length'
# Should print ~3 (one for each job)
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

## Regenerate the rendered chart

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update prometheus-community

helm template prometheus-stack \
  prometheus-community/kube-prometheus-stack \
  --version 83.6.0 \
  --namespace monitoring \
  --values deploy/kubernetes/overlays/local-prom/values-kube-prom-stack.yaml \
  --include-crds \
  > /tmp/render.yaml

# Split into CRDs + everything else (CRDs are 95% of the line count,
# don't review them with the rest of the diff)
python3 - <<'PY'
import re
docs = open("/tmp/render.yaml").read().split("\n---\n")
crds = [d for d in docs if d.strip() and re.search(r"^kind:\s*CustomResourceDefinition\b", d, re.M)]
others = [d for d in docs if d.strip() and not re.search(r"^kind:\s*CustomResourceDefinition\b", d, re.M)]
# (keep the file headers — see existing files for the comment block)
PY
```

Bump the version: edit `values-kube-prom-stack.yaml` and the `--version`
flag above, regenerate, commit both files in one MR.

## Removal

```bash
kubectl delete -k deploy/kubernetes/overlays/local-prom
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
# Then re-apply the plain `local/` overlay.
kubectl apply -k deploy/kubernetes/overlays/local
```
