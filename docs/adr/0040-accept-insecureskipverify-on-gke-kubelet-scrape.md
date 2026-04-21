# ADR-0040 — Accept `insecureSkipVerify: true` on GKE kubelet ServiceMonitor

- Status: Accepted
- Date: 2026-04-21
- Deciders: @benoit.besson
- Related: ADR-0038 (kubeletstats-receiver in LGTM), ADR-0039 (two-observability-deployment-modes), the `gke-prom/` overlay

## Context

The `gke-prom/` overlay ships the upstream `kube-prometheus-stack`
Helm chart rendered to static Kustomize manifests. That chart
declares a `ServiceMonitor` targeting the kubelet with **three
endpoints** (`/metrics`, `/metrics/cadvisor`, `/metrics/probes`).
Each endpoint is scraped over HTTPS.

The default `tlsConfig` on each endpoint is:

```yaml
tlsConfig:
  caFile: /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
  insecureSkipVerify: true
bearerTokenFile: /var/run/secrets/kubernetes.io/serviceaccount/token
```

The `caFile` points at the **Kubernetes API server's** CA (bundled
in every pod's ServiceAccount token volume). On GKE Autopilot (and
GKE Standard as of the 1.28+ kubelet-signer rework), the **kubelet
serving certificate is not signed by that CA** — it is signed by a
separate node-bootstrap CA that is NOT surfaced as a Kubernetes
resource accessible from a workload pod. Trying to verify the
kubelet's cert with the API server CA therefore always fails,
which is why the chart ships with `insecureSkipVerify: true` on
managed-K8s installs.

The TASKS.md backlog item "kubelet CA injection on GKE" proposed
mounting the kubelet CA from a Secret + adding `caFile:` to each
endpoint via a JSON6902 patch on the rendered file. This ADR
records the rejection of that path and accepts the default shield.

## Decision

**Keep `insecureSkipVerify: true` on the three kubelet endpoints
in the `gke-prom/` overlay. Do not chase a CA-injection patch.**

The residual MITM surface — the in-cluster network between the
Prometheus pod and the kubelet port — is explicitly accepted.

## Alternatives considered

### A) Mount a kubelet CA Secret + JSON6902 patch the rendered file

Concretely:
1. Provision a Secret (hand-rolled or via external-secrets) with
   the kubelet serving CA.
2. JSON6902 patch each of the three kubelet `ServiceMonitor`
   endpoints to reference `tlsConfig.ca.secret.name=<secret>`
   instead of `caFile:`, drop `insecureSkipVerify`.

**Rejected because**:
- **No stable CA source.** On GKE Autopilot the kubelet's serving
  cert is issued per-node at bootstrap by a CA that the workload
  pod does not have read access to. You cannot get the right CA
  out of the cluster. You would have to extract it from Cloud
  Operations / gcloud tooling and replicate it into a Secret —
  fragile, drift-prone, and node-rotation-unsafe (the CA itself
  rotates, not just the cert).
- **Kustomize-time coupling.** Kustomize renders static manifests
  ahead of apply. A Secret holding the kubelet CA can only
  materialise at apply-time, so the patch needs a late-bound Secret
  reference. Adds operational complexity for a property that
  downstream tooling (Mimir queries, Grafana dashboards) does not
  care about.
- **Upstream chart is on the same side.** The `kube-prometheus-stack`
  maintainers set `insecureSkipVerify: true` as the default for
  managed-K8s scenarios precisely because this path is not
  workable generically. Chasing a non-default patch means
  continuously arguing with every chart upgrade.

### B) Disable the kubelet `ServiceMonitor` entirely on GKE

Rejected because losing the kubelet scrape means losing cAdvisor
data (`container_cpu_usage_seconds_total`, `container_memory_...`),
the canonical source for per-container CPU / memory dashboards.
Replacing that data with the OTel-native `kubeletstats` receiver
(ADR-0038) is already done inside LGTM — but the `gke-prom/`
overlay explicitly keeps the Prometheus-community path as the
second lens (see ADR-0039 "Two observability deployment modes").
Disabling the ServiceMonitor there would collapse one of the two
lenses to the other.

### C) Ship with `insecureSkipVerify: true` (this ADR's decision)

**Accepted**: scrape over HTTPS still happens, the traffic is
encrypted, only the server-cert verification is skipped. The
residual attack is a MITM **inside the cluster** between Prometheus
and kubelet:10250 — a network path already protected by:

- GKE Autopilot's default NetworkPolicy posture + node-level L3
  isolation.
- The fact that only cluster-internal workloads reach `10250`
  (kubelet is not exposed on the public LoadBalancer).
- The bearer token still being validated by the kubelet (auth
  happens independently of the TLS verification).

The practical attacker would need to already be running a pod on
the same cluster AND have hijacked the `10250` traffic — at that
point the attacker has strictly more control than the metrics they
would see via a MITM'd scrape.

## Consequences

**Positive**:
- Zero operational complexity. Default chart manifests, no
  per-cluster CA plumbing, no per-upgrade patch maintenance.
- `gke-prom/` overlay stays a thin diff from `local-prom/` (4
  lines of Prometheus resource tuning), matching the ADR-0039
  goal of overlay parity.
- Prometheus still scrapes kubelet + cAdvisor + probes metrics
  over HTTPS with the SA bearer token — the data flow is
  unchanged, only the TLS-verification guarantee is weaker.

**Negative**:
- A real-world in-cluster MITM adversary could see / rewrite
  kubelet metrics in transit. Mitigated by the cluster network
  posture above; not a production security gate in this demo
  project's threat model.
- Static analysis tools that lint Prometheus manifests may flag
  `insecureSkipVerify: true` as an informational finding. Those
  findings should be marked "Acknowledged" with a pointer to
  this ADR.

## Revisit criteria

- GKE changes its kubelet-serving-cert signer to be one that is
  reachable from a workload pod (e.g. signed by the cluster CA
  bundle, or exposed via a well-known ConfigMap).
- A new `kube-prometheus-stack` release ships a first-class path
  for managed-K8s CA injection — documented pattern that doesn't
  require per-cluster custom plumbing.
- The project's threat model changes (e.g. multi-tenant cluster
  where other workloads are untrusted) such that the in-cluster
  MITM surface becomes unacceptable.

## References

- [`deploy/kubernetes/overlays/gke-prom/values-kube-prom-stack.yaml`](../../deploy/kubernetes/overlays/gke-prom/values-kube-prom-stack.yaml) — lines 219-236 inline comment block documenting the same trade-off at the source.
- [`deploy/kubernetes/overlays/gke-prom/kube-prom-stack-rendered.yaml`](../../deploy/kubernetes/overlays/gke-prom/kube-prom-stack-rendered.yaml) lines 1376-1462 — the three kubelet endpoints with `insecureSkipVerify: true`.
- `kube-prometheus-stack` chart default: https://github.com/prometheus-community/helm-charts/blob/main/charts/kube-prometheus-stack/values.yaml (search `insecureSkipVerify`).
- GKE kubelet certificate rotation docs: https://cloud.google.com/kubernetes-engine/docs/how-to/network-isolation (kubelet + worker node isolation).
- ADR-0038 — kubeletstats receiver in LGTM (the OTel-native lens).
- ADR-0039 — Two observability deployment modes (the two lenses rationale).
