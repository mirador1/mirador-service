# Spring 4 Demo

Mini service Spring Boot 4 / Java 25 utilisé comme démonstrateur technique orienté :

- structuration de socle applicatif
- observabilité
- qualité d’exploitation
- traçabilité des requêtes
- instrumentation métriques / traces
- lisibilité des choix techniques

## Ce que ce projet démontre

Ce projet ne cherche pas à prouver seulement la capacité à produire un CRUD.
L’objectif est de montrer la capacité à :

- reprendre un socle Spring Boot moderne
- le rendre observable et exploitable
- définir ce qu’il faut surveiller
- fournir des points d’entrée de diagnostic rapide
- instrumenter un service avec métriques et traces
- rendre explicites des choix de run utiles pour un rôle de responsable technique / applicatif

## Stack

- Java 25
- Spring Boot 4
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- Flyway
- Springdoc / OpenAPI
- Actuator
- Micrometer + Prometheus
- OpenTelemetry (OTLP)
- Docker / Docker Compose
- Testcontainers

## Endpoints métier

- `GET /customers`
- `POST /customers`
- `GET /customers/recent`
- `GET /customers/aggregate`

## Endpoints d’exploitation

- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
- `GET /actuator/metrics`

## Choix d’observabilité

### Corrélation des requêtes
Chaque requête HTTP reçoit un `X-Request-Id` :
- réutilisé si fourni par le client
- généré sinon
- renvoyé dans la réponse
- injecté dans les logs

### Métriques exposées
Exemples :
- `customer.created.count`
- `customer.create.duration`
- `customer.find_all.duration`
- `customer.aggregate.duration`
- `customer.recent.buffer.size`

### Tracing
Le projet exporte les traces en OTLP.
En local, on peut brancher un backend OTLP pour visualiser les spans.

### Health checks
Le projet expose :
- health globale
- liveness
- readiness
- un check DB simple (`select 1`)

## Démarrage local

### Base seule pour le développement local
```bash
./run.sh db
mvn spring-boot:run
