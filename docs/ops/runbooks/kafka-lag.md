# Alert: `MiradorKafkaConsumerLagHigh`

Fired when `max(kafka_consumer_fetch_manager_records_lag) > 1000` for ≥
5 min — at least one partition has a thousand records the consumer
hasn't caught up with. Warning-tier.

## Quick triage (60 seconds)

```bash
# 1. Which consumer group + partition is lagging?
curl -s 'http://localhost:9090/api/v1/query' --data-urlencode 'query=
  topk(5, max by (consumer_group, topic, partition) (
    kafka_consumer_fetch_manager_records_lag{job=~"mirador.*"}
  ))
' | jq '.data.result[] | {
  group: .metric.consumer_group,
  topic: .metric.topic,
  partition: .metric.partition,
  lag: .value[1]
}'

# 2. Is the consumer stuck, or just slow?
curl -sS http://localhost:8080/actuator/health | jq '.components.kafka'
```

## Likely root causes

1. **Poison pill in the DLT path** — a message the consumer can't
   deserialise blocks the partition. Spring Kafka's default
   `ErrorHandler` re-tries 3x then moves on, but a misconfigured
   consumer might loop. Check app logs for repeated
   `SerializationException`.
2. **Consumer is slow, not stuck** — `/enrich` does a synchronous
   Ollama LLM call per message. If Ollama slows down, the consumer
   backs up. Check latency on the `customer.reply` topic handler.
3. **Partition rebalance in progress** — consumer group just rebalanced,
   temporary lag spike (< 2 min usually). Expected to self-heal.
4. **Kafka broker disk pressure** — broker slow to serve fetches.
   `kubectl -n infra describe pod kafka-0` — check for disk-pressure
   taints.

## Commands to run

```bash
# Kafka UI — fastest way to see partition state
open http://localhost:8090  # kafka-ui service in docker-compose

# Consumer group state from CLI
kubectl -n infra exec -it kafka-0 -- \
  kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group mirador-enrich

# Recent consumer errors in Loki
#   {app="mirador"} |~ "KafkaException|SerializationException" | json

# DLT depth — if this is growing, poison pills are accumulating
kubectl -n infra exec -it kafka-0 -- \
  kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic customer.request.DLT
```

## Fix that worked last time

- Poison pill in `customer.request` → seeked past offset via CLI:
  `kafka-consumer-groups.sh --reset-offsets --to-offset <n> --execute`.
  Lag cleared in 30 s.
- Ollama slow on backlog → temporarily bumped `max.poll.records` down
  to 1 so each LLM call doesn't block a batch.

## When to escalate

- Lag > 100 000 and still growing 10 min after detection → consumer
  permanently stuck. Restart the consumer pod; if that fails, the
  consumer group coordinator may be corrupted (rare).
- Lag on `customer.reply` topic (replies back to the UI) → user-facing
  feature is broken. Raise to critical and escalate.
