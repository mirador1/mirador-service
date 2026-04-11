# Spring Boot 4 – Observable Service

A minimal Spring Boot 4 / Java 25 service used as a demonstrator for:

- application foundation structuring
- observability (metrics, logs, tracing)
- operational quality
- fast incident diagnosis

---

## 🎯 Goal

This project is not meant to demonstrate a simple CRUD, but the ability to:

- make a service observable
- define what needs to be monitored
- expose diagnostic endpoints
- structure a minimal operational environment

---

## 🧱 Stack

- Java 25
- Spring Boot 4
- Spring Web / JPA
- PostgreSQL
- Flyway
- Actuator
- Micrometer + Prometheus
- Grafana
- OpenTelemetry
- **Spring Kafka** (async and synchronous request-reply patterns)
- Docker / Docker Compose
- Testcontainers

---

## 🚀 Business endpoints

- `GET /customers`
- `POST /customers`
- `GET /customers/recent`
- `GET /customers/aggregate`
- `GET /customers/{id}/enrich` — synchronous Kafka request-reply

---

## 🔍 Operational endpoints

To call alongside: `curl -s http://localhost:8080`
- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`
- `/actuator/metrics`

---

# 📊 Observability

## Grafana Dashboard

![Grafana Dashboard](docs/screenshots/grafana-overview.png)

http://localhost:3000/

This dashboard shows:

- HTTP throughput
- endpoint latency
- number of customer creations
- in-memory buffer size

## Prometheus

http://localhost:9090/

![Prometheus Dashboard](docs/screenshots/prometheus-overview.png)

Maximum response time for endpoints called on the server.

---

## Grafana Dashboard with OpenTelemetry

![Grafana OpenTelemetry Dashboard](docs/screenshots/grafana-otel-overview.png)

http://localhost:3001/

This dashboard shows the OpenTelemetry view (Explore Tempo) with the DB span.
http://localhost:3001/explore

---

## 📨 Kafka — demonstrated patterns

This project uses the application as both **producer and consumer of its own messages** to illustrate two distinct Kafka patterns.

### Topics

| Topic | Role |
|---|---|
| `customer.created` | Async pattern — event published after each customer creation |
| `customer.request` | Sync pattern — enrichment request |
| `customer.reply` | Sync pattern — enrichment response |

---

### Pattern 1 — Asynchronous (fire-and-forget)

`POST /customers` creates the customer in the database, then publishes a `CustomerCreatedEvent` on `customer.created` **without waiting** for a response.

A `@KafkaListener` in the same app consumes the event and logs:
```
kafka_event type=CustomerCreatedEvent id=1 name=Alice
```

**Flow:**
```
POST /customers → CustomerService → KafkaTemplate.send("customer.created") → immediate 200
                                                  ↓ (async)
                                    CustomerEventListener.onCustomerCreated()
```

**Test:**
```bash
curl -s -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'
# Watch the logs: kafka_event type=CustomerCreatedEvent id=1 name=Alice
```

**Metrics:**
```bash
curl -s http://localhost:8080/actuator/metrics/kafka.customer.created.processed
```

---

### Pattern 2 — Synchronous (Kafka request-reply)

`GET /customers/{id}/enrich` sends a Kafka request and **blocks until the response** (timeout: 5s).

The same app processes the request via `@KafkaListener` + `@SendTo`, computes a `displayName`, and replies on `customer.reply`. Correlation is handled automatically by `ReplyingKafkaTemplate`.

**Flow:**
```
GET /customers/{id}/enrich
  → ReplyingKafkaTemplate.sendAndReceive("customer.request")  [blocking]
      ↓
  CustomerEnrichHandler.handleEnrichRequest()  [@KafkaListener + @SendTo]
      ↓
  → reply on "customer.reply"
  → return EnrichedCustomerDto { displayName: "Alice <alice@example.com>" }
```

**Test (create a customer first, then enrich):**
```bash
# 1. Create a customer
curl -s -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'
# Response: {"id":1,"name":"Alice","email":"alice@example.com"}

# 2. Enrich via Kafka request-reply
curl -s http://localhost:8080/customers/1/enrich
# Response: {"id":1,"name":"Alice","email":"alice@example.com","displayName":"Alice <alice@example.com>"}
```

**Metrics:**
```bash
curl -s http://localhost:8080/actuator/metrics/kafka.customer.enrich.handled
curl -s http://localhost:8080/actuator/metrics/customer.enrich.duration
```

---

### Start Kafka locally

Kafka is included in `docker-compose.yml` (KRaft, no ZooKeeper):

```bash
./run.sh all     # starts PostgreSQL + Kafka + the application
# or separately:
docker compose up -d kafka
```

Kafka is accessible on `localhost:9092`.

---

## Sample exposed metrics

```bash
curl -s http://localhost:8080/actuator/prometheus | grep customer
```

## Diagnostic scenario 1 — PostgreSQL unavailability

### Setup
PostgreSQL is stopped while the application keeps running.

### Verification
```bash
curl -s http://localhost:8080/actuator/health/readiness
```

## Diagnostic scenario 2 — latency on `/customers/aggregate`

### Goal
Show how to qualify a response time problem on a specific endpoint using metrics and the dashboard.

### Load
```bash
for i in {1..100}; do
  curl -s http://localhost:8080/customers/aggregate > /dev/null
done
```

### Verification
```bash
curl -s http://localhost:8080/actuator/prometheus
```
