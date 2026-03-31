# spring-4-demo

Mini service Spring Boot 4 / Java 25 utilisé comme démonstrateur technique orienté :

- structuration de socle applicatif
- observabilité
- qualité d’exploitation
- traçabilité des requêtes
- instrumentation métriques / traces
- lisibilité de choix techniques

## Ce que ce projet démontre

Ce projet n’a pas été pensé comme un simple CRUD de démonstration.
L’objectif est de montrer la capacité à :

- reprendre un socle Spring Boot moderne
- le rendre exploitable et observable
- expliciter les choix de supervision
- définir des points de diagnostic rapides
- instrumenter un service avec métriques et traces
- fournir des artefacts utiles en contexte RUN / support / pilotage technique

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

### 1. Corrélation des requêtes
Chaque requête HTTP reçoit un `X-Request-Id` :
- réutilisé si fourni par le client
- généré sinon
- renvoyé dans la réponse
- injecté dans les logs

### 2. Métriques exposées
Exemples :
- `customer.created.count`
- `customer.create.duration`
- `customer.find_all.duration`
- `customer.aggregate.duration`
- `customer.recent.buffer.size`

### 3. Tracing
Le projet exporte les traces en OTLP.
En local, on peut brancher un backend OTLP pour visualiser les spans.

### 4. Health checks
Le projet expose :
- health globale
- liveness
- readiness
- un check DB simple (`select 1`)

## Démarrage local

### Base de données
Le projet suppose PostgreSQL local :

- host: `localhost`
- port: `5432`
- db: `demo`
- user: `demo`
- password: `demo`

### Lancer l’application
```bash
./mvnw spring-boot:run
