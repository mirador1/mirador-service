# ADR-0005: In-cluster Kafka (not Managed) for cost reasons

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

The app uses Kafka for an async request-reply enrichment pipeline
(`customer.request` → consumer → `customer.reply`). We compared managed
offerings vs running a Kafka `Deployment` in-cluster:

| Option                | Approx. monthly cost | Ops burden                 |
| --------------------- | -------------------- | -------------------------- |
| Google Managed Kafka  | ~$1,000/month        | Very low                   |
| Confluent Cloud Basic | ~$250/month          | Low                        |
| Amazon MSK Serverless | ~$150-300/month      | Low                        |
| In-cluster KRaft mode | ~$10/month (Autopilot pod) | Medium (upgrades, PVC) |

For a demo / portfolio project with low volume (<100 msg/s), paying
$150-1000/month is not justifiable.

## Decision

Run Kafka **in-cluster** as a K8s Deployment using KRaft mode (no
Zookeeper), single broker, no persistent volume (topics are ephemeral —
the reply pattern is synchronous from the app's point of view via a
response promise with a timeout).

Manifest: `deploy/kubernetes/base/stateful/kafka.yaml`.

A migration path to Managed Kafka is documented in
`deploy/terraform/gcp/kafka.tf` (commented out) for the day we need
durability / multi-AZ.

## Consequences

### Positive

- ~$10/month vs $150-1000/month.
- Simple mental model: same YAML in every overlay.
- Fast iteration — restart the broker in 5s for schema changes during dev.

### Negative

- No durability guarantee across pod restarts. Acceptable because our
  reply-pattern timeout is 5s — messages dropped are retried by the client.
- No multi-broker replication. A broker restart = ~2s of unavailability.
- We are responsible for upgrading the KRaft version.

### Neutral

- If volume grows past ~1000 msg/s or we add a second service that needs
  the same Kafka, we migrate to Google Managed Kafka (Terraform path
  already scaffolded).

## Alternatives considered

### Alternative A — Google Managed Kafka

Rejected: $1,000/month minimum cluster cost is prohibitive. Best option
technically but wildly over-provisioned for the workload.

### Alternative B — Confluent Cloud

Rejected: $250/month is the cheapest "Basic" tier; still 25× more than
in-cluster. Also adds a third-party vendor in the data path.

### Alternative C — Amazon MSK Serverless

Rejected: we're on GCP. Cross-cloud data path = egress costs + latency.

### Alternative D — Redis Streams or Pub/Sub

Rejected: we use Redis for caching (different SLO). Google Pub/Sub has
exactly-once semantics but no Kafka client compatibility — would require
rewriting the consumer code.

## References

- `deploy/terraform/gcp/kafka.tf` — commented-out Managed Kafka migration.
- ADR-0003 — sister decision on Cloud SQL. Different tradeoff because
  Postgres is fundamentally stateful.
