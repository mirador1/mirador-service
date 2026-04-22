# Backend returns 503

## Quick triage (30 seconds)

```bash
# 1. Is the backend actually up?
curl -sS http://localhost:8080/actuator/health | jq .

# 2. If 503, which component is DOWN?
curl -sS http://localhost:8080/actuator/health | jq '.components | map_values(.status)'
```

Every 503 from Spring Boot means **at least one composite health
component is DOWN**. The response breaks it down — DB, Redis, Kafka,
Ollama, disk space. Pick the DOWN one and jump to the right runbook.

## Likely root causes (in order of frequency)

1. **Postgres unreachable** — most common on kind after a cluster
   restart; the CSI-backed PVC didn't rebind. `components.db` = DOWN.
2. **Redis unreachable** — cache check fails. `components.redis` = DOWN.
3. **Kafka broker slow or disconnected** — `components.kafka` = DOWN.
4. **Ollama unreachable** — expected when Ollama isn't running, and
   **this is deliberately a readiness-only flag** so the pod doesn't
   get restarted. `components.ollama` = DOWN but core API works
   (see "Known limitations" in the repo README).
5. **Disk space < 10%** — only under heavy profiling.

## Commands to run

```bash
# Pod state
kubectl -n app get pods -l app=mirador -o wide
kubectl -n app logs deploy/mirador --tail=50

# Readiness vs liveness — if liveness is UP but readiness DOWN,
# the pod is alive but deliberately refusing traffic
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness

# Last 20 error-rate lines (LokiQL)
# {app="mirador"} |~ "ERROR" | json
# via Grafana Explore: http://localhost:23000/explore (prod tunnel)
```

## Fix that worked last time

- **kind Postgres DOWN** — the postgres-data PVC got bound to a
  different node after a reboot. Delete the pod, statefulset
  controller recreates on the correct node:
  ```
  kubectl delete pod postgresql-0 -n infra
  ```
- **Kafka slow start** — the broker needs ~30s after pod start to be
  ready. If the mirador pod started first and health-checked against
  an unready broker, it sticks in 503. Wait 60s and re-check.
- **Ollama OOM** — deliberate degradation, the
  `/customers/{id}/bio` endpoint falls back to "Bio temporarily
  unavailable." (see the circuit breaker in `BioService`). Not an
  incident for the core API.

## When to escalate

If `/actuator/health` itself is unreachable (connection refused at the
HTTP level, not just a 503 body), the pod is dead. Recreate:

```bash
kubectl delete pod -n app -l app=mirador
```

If that doesn't recover within 60s, the cluster may be in a bad state.
On the ephemeral-cluster pattern the fastest recovery is destroy +
recreate: `bin/cluster/demo/down.sh && bin/cluster/demo/up.sh`.
