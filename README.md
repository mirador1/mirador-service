# Spring Boot 4 – Service observable

Mini service Spring Boot 4 / Java 25 utilisé comme démonstrateur de :

- structuration d’un socle applicatif
- observabilité (métriques, logs, tracing)
- qualité d’exploitation
- diagnostic rapide en cas d’incident

---

## 🎯 Objectif

Ce projet ne vise pas à démontrer un simple CRUD, mais la capacité à :

- rendre un service observable
- définir ce qu’il faut surveiller
- exposer des points de diagnostic
- structurer un environnement d’exploitation minimal

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
- **Spring Kafka** (patterns async et synchrone request-reply)
- Docker / Docker Compose
- Testcontainers

---

## 🚀 Endpoints métier

- `GET /customers`
- `POST /customers`
- `GET /customers/recent`
- `GET /customers/aggregate`
- `GET /customers/{id}/enrich` — request-reply Kafka synchrone

---

## 🔍 Endpoints d’exploitation

A appeler en complément de la commande: `curl -s http://localhost:8080`
- `/actuator/health`
- `/actuator/health/liveness`
- `/actuator/health/readiness`
- `/actuator/prometheus`
- `/actuator/metrics`

---

# 📊 Observabilité

## Dashboard Grafana

![Dashboard Grafana](docs/screenshots/grafana-overview.png)

http://localhost:3000/

Ce dashboard montre :

- débit HTTP
- latence des endpoints
- nombre de créations clients
- taille du buffer en mémoire

## Prometheus

http://localhost:9090/

![Dashboard Grafana](docs/screenshots/prometheus-overview.png)

Ici on voit la durée maximal des endpoints appelsés sur le serveur
---

## Dashboard Grafana avec openTelemetry

![Dashboard Grafana](docs/screenshots/grafana-otel-overview.png)

http://localhost:3001/

Ce dashboard montre la visu otel (Explore Tempo) avec le span BD.
http://localhost:3001/explore

---

## 📨 Kafka — patterns démontrés

Ce projet utilise l'application comme **producteur et consommateur de ses propres messages** pour illustrer deux patterns Kafka distincts.

### Topics

| Topic | Rôle |
|---|---|
| `customer.created` | Pattern async — event publié après chaque création client |
| `customer.request` | Pattern sync — requête d'enrichissement |
| `customer.reply` | Pattern sync — réponse d'enrichissement |

---

### Pattern 1 — Asynchrone (fire-and-forget)

`POST /customers` crée le client en base, puis publie un `CustomerCreatedEvent` sur `customer.created` **sans attendre** de réponse.

Un `@KafkaListener` dans la même app consomme l'event et logue :
```
kafka_event type=CustomerCreatedEvent id=1 name=Alice
```

**Flux :**
```
POST /customers → CustomerService → KafkaTemplate.send("customer.created") → retour 200 immédiat
                                                  ↓ (async)
                                    CustomerEventListener.onCustomerCreated()
```

**Tester :**
```bash
curl -s -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'
# Observer les logs : kafka_event type=CustomerCreatedEvent id=1 name=Alice
```

**Métriques :**
```bash
curl -s http://localhost:8080/actuator/metrics/kafka.customer.created.processed
```

---

### Pattern 2 — Synchrone (Kafka request-reply)

`GET /customers/{id}/enrich` envoie une requête Kafka et **bloque jusqu'à la réponse** (timeout : 5s).

La même app traite la requête via `@KafkaListener` + `@SendTo`, calcule un `displayName`, et répond sur `customer.reply`. La corrélation est gérée automatiquement par `ReplyingKafkaTemplate`.

**Flux :**
```
GET /customers/{id}/enrich
  → ReplyingKafkaTemplate.sendAndReceive("customer.request")  [bloquant]
      ↓
  CustomerEnrichHandler.handleEnrichRequest()  [@KafkaListener + @SendTo]
      ↓
  → réponse sur "customer.reply"
  → retour EnrichedCustomerDto { displayName: "Alice <alice@example.com>" }
```

**Tester (créer d'abord un client, puis enrichir) :**
```bash
# 1. Créer un client
curl -s -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'
# Réponse: {"id":1,"name":"Alice","email":"alice@example.com"}

# 2. Enrichir via Kafka request-reply
curl -s http://localhost:8080/customers/1/enrich
# Réponse: {"id":1,"name":"Alice","email":"alice@example.com","displayName":"Alice <alice@example.com>"}
```

**Métriques :**
```bash
curl -s http://localhost:8080/actuator/metrics/kafka.customer.enrich.handled
curl -s http://localhost:8080/actuator/metrics/customer.enrich.duration
```

---

### Démarrer Kafka en local

Kafka est inclus dans `docker-compose.yml` (KRaft, sans ZooKeeper) :

```bash
./run.sh all     # démarre PostgreSQL + Kafka + l'application
# ou séparément :
docker compose up -d kafka
```

Kafka est accessible sur `localhost:9092`.

---

## Exemple de métriques exposées

```bash
curl -s http://localhost:8080/actuator/prometheus | grep customer

## Scénario de diagnostic 1 — indisponibilité PostgreSQL

### Mise en situation
La base PostgreSQL est arrêtée alors que l’application continue de tourner.

### Vérification
```bash
curl -s http://localhost:8080/actuator/health/readiness

## Scénario de diagnostic 2 — latence sur `/customers/aggregate`

### Objectif
Montrer comment qualifier un problème de temps de réponse sur un endpoint spécifique à partir des métriques et du dashboard.

### Mise en charge
```bash
for i in {1..100}; do
  curl -s http://localhost:8080/customers/aggregate > /dev/null
done

### Vérification
```bash
curl -s http://localhost:8080/actuator/prometheus
