# Alert: `MiradorHighErrorRate`

Fired when `mirador:http_error_ratio:5m > 0.05` for ≥ 5 min — more than 1
request in 20 is returning 5xx. Source rule:
`base/observability-prom/mirador-alerts.yaml`.

## Quick triage (60 seconds)

```bash
# 1. Which endpoint is the hotspot? Break down by URI on the RED dashboard:
#    Grafana → Mirador RED → "Error rate by URI" panel
#    OR direct PromQL in Prom UI:
#
#    topk(5, sum by (uri) (rate(
#      http_server_requests_seconds_count{status=~"5..",job=~"mirador.*"}[5m]
#    )))

# 2. Is a downstream dep down? Check actuator health:
curl -sS http://localhost:8080/actuator/health | jq '.components'
```

## Likely root causes (from most to least common)

1. **Downstream outage — DB, Redis, Kafka, Ollama** — error spike
   correlates with `components.db == DOWN` etc. Jump to `backend-503.md`.
2. **Circuit breaker flip on a fast path** — the Ollama circuit (`BioService`)
   or Kafka enrich circuit opens and short-circuits to 5xx. Check
   `/actuator/circuitbreakers`.
3. **Deploy regression** — error rate spikes right at `deploy:gke` time.
   Correlate with `kubectl rollout history`.
4. **Traffic pattern change** — abnormal client behaviour (e.g. clients
   hammering `/customers/{id}/bio` which legitimately 503s when the
   circuit is open). Not a real outage, but still worth silencing during
   the demo.

## Commands to run

```bash
# Where is the error concentrated?
curl -s http://localhost:9090/api/v1/query?query='
  topk(5, sum by (uri, status) (rate(
    http_server_requests_seconds_count{status=~"5..",job=~"mirador.*"}[5m]
  )))
' | jq .

# Deploy history (GKE)
kubectl -n app rollout history deployment/mirador
kubectl -n app rollout status deployment/mirador

# Circuit breaker state
curl -s http://localhost:8080/actuator/circuitbreakers | jq .

# Recent errors in Loki (fronted via Grafana Explore):
#   {app="mirador"} |~ "ERROR" | json | __error__=""
```

## Fix that worked last time

- Circuit on Ollama open → restart Ollama (expected degradation, not an
  app bug). The `/bio` endpoint auto-recovers within the circuit's
  wait-duration.
- DB connection pool exhausted → raise HikariCP `maximumPoolSize` in
  `application.yml` (default 10 is tight under chaos-load demo).

## When to escalate

- Error rate > 50 % → immediate rollback:
  `kubectl -n app rollout undo deployment/mirador`
- Errors persist after rollback → infra issue, not app. Check the
  GCP status dashboard + `kubectl get events -A` for node-level problems.
