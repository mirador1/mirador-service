# Alert: `MiradorBackendDown` / `MiradorBackendAbsent`

Fired by one of two PrometheusRule expressions in
`deploy/kubernetes/base/observability-prom/mirador-alerts.yaml`:

- `MiradorBackendDown` — `up{job=~"mirador.*"} == 0` for ≥ 2 min. Prometheus
  CAN scrape the target but gets a non-200 on `/actuator/prometheus`.
- `MiradorBackendAbsent` — `absent(up{job=~"mirador.*"})` for ≥ 5 min. NO
  target at all — the ServiceMonitor lost its selector, or the pod is gone.

## Quick triage (30 seconds)

```bash
# 1. Is the pod alive?
kubectl -n app get pods -l app=mirador -o wide

# 2. Does the Service have endpoints?
kubectl -n app get endpoints mirador

# 3. Does the operator see the ServiceMonitor?
kubectl -n monitoring get servicemonitor mirador -o yaml \
  | yq '.metadata.labels'
```

If endpoints are empty but pod is `Running`, the Service selector is wrong.
If `servicemonitor` labels don't include `release: prometheus-stack`, the
operator silently ignores it — this is the same trap that would make any
new ServiceMonitor invisible.

## Likely root causes

1. **Pod OOMKilled** — heap tight after a recent image bump. Check `kubectl
   describe pod` for `Reason: OOMKilled` on the previous container state.
2. **Readiness stays FAIL** — pod is up but `/actuator/health/readiness`
   reports DOWN → endpoints stripped. Jump to `backend-503.md`.
3. **ServiceMonitor label drift** — someone removed the `release` label
   while editing. Prometheus shows zero targets for `mirador`; rules then
   fire `MiradorBackendAbsent`.
4. **Namespace network policy** — the default-deny in `infra` blocks
   `monitoring → app:8080` on port `/actuator/prometheus`. Check
   `kubectl -n app describe networkpolicy`.

## Commands to run

```bash
# Prometheus target state (best single-source-of-truth)
# Port-forward the Prometheus UI and check Status → Targets:
kubectl -n monitoring port-forward svc/prometheus-kube-prom-stack-prometheus 9090:9090
open http://localhost:9090/targets

# If Prom sees mirador in "down" state, click the endpoint URL to get the
# exact scrape error (DNS, TLS, connection refused, HTTP 4xx/5xx).

# Pod logs — last startup + any error bursts
kubectl -n app logs deploy/mirador --tail=80 --previous  # pre-crash logs
kubectl -n app logs deploy/mirador --tail=80             # current

# If MiradorBackendAbsent: confirm the ServiceMonitor is selected
kubectl -n monitoring get prometheus -o yaml \
  | yq '.items[0].spec.serviceMonitorSelector'
```

## Fix that worked last time

- Label drift on ServiceMonitor — add back `release: prometheus-stack`
  and restart `prometheus-operator` to force a reload.
- OOMKilled — increase `resources.limits.memory` via the app overlay
  (the 512 Mi default is tight for the Ollama + Tempo child observations).

## When to escalate

- Pod `CrashLoopBackOff` with non-health-related errors → treat as a
  code regression; roll back via `kubectl rollout undo`.
- No pod at all + `kubectl get deploy` empty → the app overlay failed
  to apply. Re-run `kubectl apply -k deploy/kubernetes/overlays/<env>`.
