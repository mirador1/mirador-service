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
- OpenTelemetry (OTLP)
- Grafana / LGTM
- Docker / Docker Compose
- Testcontainers

---

## 🚀 Endpoints métier

- `GET /customers`
- `POST /customers`
- `GET /customers/recent`
- `GET /customers/aggregate`

---

## 🔍 Endpoints d’exploitation

- `/actuator/health`
- `/actuator/health/readiness`
- `/actuator/prometheus`
- `/actuator/metrics`

---

# 📊 Preuves d’observabilité

## Dashboard Grafana

![Dashboard Grafana](docs/screenshots/grafana-overview.png)

Ce dashboard montre :

- débit HTTP
- latence des endpoints
- nombre de créations clients
- taille du buffer en mémoire

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
