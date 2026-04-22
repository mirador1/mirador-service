# Kafka `/customers/{id}/enrich` times out

## Quick triage (30 seconds)

```bash
# Is the request actually reaching Kafka? Grep the backend logs for the
# structured key added in commit 4782081:
kubectl logs -n app deploy/mirador --tail=200 | grep -E "kafka_enrich_"
```

Expected in normal operation:
- `kafka_enrich_reply id=<n> displayName=...` — success
- Absence of these lines after repeated calls → the request isn't
  reaching the listener (broker up? partition assigned?).

## Likely root causes (in order of frequency)

1. **Ollama is slow or saturated** — Kafka reply-waiter times out at 5s
   (configured via `app.kafka.enrich.timeout-seconds`). Ollama under
   load or loading a cold model easily exceeds that.
2. **Broker under chaos injection** — Chaos Mesh `NetworkChaos` CR
   targeting the broker can delay messages past the timeout. Check
   `kubectl get networkchaos -A`.
3. **Consumer group rebalancing** — a recent redeploy leaves the
   consumer lagging for 30s while partitions are reassigned. Visible
   as one timeout burst then recovery.
4. **Resource starvation** — backend pod at its CPU quota → Kafka
   consumer thread doesn't get CPU time.
5. **Topic deleted or auto-created with wrong partition count**.

## Commands to run

```bash
# 1. Broker health
kubectl -n infra exec -it statefulset/kafka -- \
  kafka-topics.sh --bootstrap-server localhost:9092 --describe \
  --topic customer.request

# 2. Consumer lag
kubectl -n infra exec -it statefulset/kafka -- \
  kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe \
  --group mirador-enrich

# 3. Active chaos experiments
kubectl get networkchaos,podchaos,stresschaos -A

# 4. Current rate of timeouts (Loki via Grafana Explore)
#    {app="mirador"} |~ "kafka_enrich_timeout"
#    Grafana: bin/cluster/port-forward/prod.sh then http://localhost:23000/explore

# 5. The Golden Signals dashboard in Grafana has a Kafka panel.
```

## Fix that worked last time

The 2026-04-19 incident: Chaos Mesh `mirador-to-postgres-delay` was
left active on the local kind cluster after a demo, delaying DB writes
transitively under the Kafka listener (the listener stores the enriched
customer before returning). Delete the CR:

```bash
kubectl delete networkchaos mirador-to-postgres-delay -n app
```

The 2026-04-12 incident: Ollama pod was OOM-killed; bio generation
(which is downstream of enrich in a few flows) cascaded into enrich
timeouts. Fix: restart Ollama pod with a larger memory request.

## When to escalate

If the timeout rate is >10% after 2 min of normal traffic AND all the
commands above show healthy state, the issue is likely a code
regression in `CustomerEnrichHandler` or `ReplyingKafkaTemplate`
config. At that point:

1. Roll back to the previous container image.
2. Open a MR with the specific error trace from Tempo
   (`{service.name="mirador", http.route="/customers/*/enrich"}`).
3. Destroy the ephemeral cluster if demo time is short:
   `bin/cluster/demo/down.sh` then `bin/cluster/demo/up.sh`.
