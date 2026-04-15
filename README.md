![Mirador Service](banner.svg)

![Java 25](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white) ![Spring Boot 4](https://img.shields.io/badge/Spring_Boot-4-6DB33F?logo=springio&logoColor=white) ![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white) ![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-black?logo=apachekafka&logoColor=white) ![Redis](https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white) ![Angular 21](https://img.shields.io/badge/Angular-21-DD0031?logo=angular&logoColor=white) ![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white) ![GitLab CI](https://img.shields.io/badge/GitLab_CI-FC6D26?logo=gitlab&logoColor=white) ![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-7F52FF?logo=opentelemetry&logoColor=white)

# Mirador — Observable Customer API

This project has one goal: demonstrate what it takes to diagnose an incident on a backend service.
The stack is built around that scenario — not around the technologies themselves.

## Table of contents

- [Architecture — dev (Docker Compose)](#architecture)
- [Architecture — production (Kubernetes)](#architecture--production-kubernetes)
- [Quick start](#quick-start)
- [What this demonstrates](#what-this-demonstrates)
- [Running locally](#running-locally)
- [Local Kubernetes (kind)](#local-kubernetes-kind)
- [CI/CD](#cicd)
- [Screenshots](#screenshots)
- [Detailed documentation](#detailed-documentation)

---

## Architecture

```mermaid
flowchart LR
    Browser(["Browser / curl"])

    subgraph SB["🔭 Mirador Service"]
        F["🛡️ Filters\nRate limit · JWT · Idempotency"]
        API["🔌 REST API"]
        SVC["⚙️ Domain\n+ Scheduler"]
    end

    subgraph Infra["🐳 Infrastructure"]
        PG[("PostgreSQL")]
        KF[["Kafka"]]
        RD[("Redis")]
        KC["Keycloak"]
        OL["Ollama"]
    end

    OTEL["📡 OTel → Grafana\nTempo · Loki · Prometheus"]

    Browser --> F --> API --> SVC
    SVC <--> PG & KF & RD
    SVC --> OL
    F -.->|JWT verify| KC
    SVC -.-> OTEL
```

---

## Architecture — production (Kubernetes)

When deployed to a Kubernetes cluster the two Docker images are served behind a single
Nginx Ingress on one hostname — eliminating CORS entirely.

```
Internet (HTTPS — TLS via cert-manager)
    │
    ▼
Nginx Ingress (ingress-nginx)
  /api/** → mirador-service:8080   (Spring Boot 4, 2 replicas, HPA 1–5)
  /**     → mirador-ui:80          (Angular 21 + Nginx, 2 replicas)
    │
    ▼
namespace: infra
  PostgreSQL 17  — StatefulSet, 10 Gi PVC, Flyway migrations
  Redis 7        — Deployment, 128 MB, JWT blacklist + ring buffer
  Kafka (KRaft)  — Deployment, topics: created / request / reply
```

> **Same-origin design**: the browser always calls `https://app.example.com/api/…`.
> Nginx strips `/api` and proxies to the backend. No CORS headers needed.

**CI deployment targets** (deploy stage in `.gitlab-ci.yml`):

| Target | Trigger |
|--------|---------|
| GKE Autopilot | Auto on `main` push |
| AWS EKS | Manual |
| Azure AKS | Manual |
| Google Cloud Run | Manual (serverless) |
| Fly.io | Manual (PaaS) |
| k3s / bare metal | Manual |

---

## Quick start

### Prerequisites

| Tool | Version | Install |
|---|---|---|
| **Java** | 17 / 21 / 25 (default: 25) | [sdkman.io](https://sdkman.io) `sdk install java 25-open` |
| **Docker Desktop** | 4.x | [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/) |
| **Maven** | via `./mvnw` | bundled Maven Wrapper — no installation needed |
| **Git** | any | pre-installed on most systems |

> Multi-version support: Java 17/21/25 × Spring Boot 3/4 × Maven 3/4 — see Maven profiles in `pom.xml`.

Optional (for frontend):

| Tool | Version | Install |
|---|---|---|
| **Node.js** | 22 LTS | [nodejs.org](https://nodejs.org) or `nvm install 22` |
| **npm** | 10 | bundled with Node 22 |

---

### First-time setup

```bash
git clone https://gitlab.com/benoit.besson/mirador-service.git && cd mirador-service
bash run.sh all
```

That's it. Docker starts automatically. Sign in at http://localhost:8080/swagger-ui.html with **admin / admin**.

> **With the Angular frontend** (second terminal):
> ```bash
> git clone https://gitlab.com/benoit.besson/mirador-ui.git && cd mirador-ui
> bash run.sh
> ```
> UI at http://localhost:4200 — delegates infrastructure to the backend `run.sh`.

---

### Step-by-step (manual)

```bash
# Start everything (Docker + observability + app)
./run.sh all

# Or step by step:
docker compose up -d              # infra (DB, Kafka, Redis, Ollama, Keycloak, admin tools)
./run.sh obs                      # observability (Grafana, Prometheus, Tempo, Zipkin, Pyroscope)
./run.sh app                      # Spring Boot app

# Get a token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

# Create a customer (20 demo customers are pre-loaded by Flyway)
curl -s -X POST http://localhost:8080/customers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Alice","email":"alice@example.com"}'

# Generate traffic for dashboards
./run.sh simulate

# Check status of all services
./run.sh status
```

---

## What this demonstrates

### Core — observability and diagnosis

| Capability | How it's implemented |
|---|---|
| Distributed tracing | OpenTelemetry → Tempo (via LGTM on port 3001); DB spans via `datasource-micrometer` |
| Metrics and latency histograms | Micrometer → Prometheus → Grafana (p50/p95/p99, custom counters) |
| Structured logs correlated with traces | OTel log exporter → Loki, trace ID injected in every log line |
| Health probes | Custom indicators for DB, Kafka, Redis, Ollama; liveness/readiness groups |
| Operational endpoints | `/actuator/health/readiness`, `/actuator/prometheus`, Swagger UI |

### Additional patterns

| Pattern | What it illustrates |
|---|---|
| Kafka fire-and-forget + request-reply | Async decoupling vs sync correlation with built-in timeout |
| JWT + optional Keycloak + API key | Three auth modes in one filter chain |
| Resilience4j circuit breaker + retry | Graceful degradation when an external dependency fails |
| Bucket4j rate limiting | Token-bucket per IP, enforced before business logic |
| WebSocket notifications | Real-time push on customer creation via STOMP |
| Cursor pagination + search | Efficient pagination + full-text search on name/email |
| Batch import + CSV export | Bulk operations with streaming response |
| Virtual threads (Project Loom) | Parallel sub-tasks in `AggregationService` |

### Security

| Pattern | What it illustrates |
|---|---|
| OWASP security headers | CSP, X-Frame-Options, nosniff, Referrer-Policy |
| Brute-force protection | IP lockout after 5 failed login attempts (15 min) |
| Input sanitization | `@Size(max=255)`, request body limit (1 MB) |
| Audit logging | DB-backed `audit_event` table — who, what, when, IP |
| SQL injection / XSS demos | Vulnerable vs safe endpoints for education |
| OWASP Dependency-Check | CVE scan on all dependencies |

---

## Running locally

```bash
./run.sh all            # start everything (infra + obs + app)
./run.sh restart        # stop + restart everything (keeps data)
./run.sh stop           # stop app + all containers
./run.sh nuke           # full cleanup — containers, volumes, build artifacts
./run.sh status         # check status of all services
./run.sh simulate       # generate traffic (60 iterations, 2s pause)

./run.sh test           # unit tests (no Docker)
./run.sh integration    # integration tests (Testcontainers)
./run.sh verify         # lint + unit + integration (mirrors CI)
./run.sh security-check # OWASP Dependency-Check (CVE scan)
```

Pre-push hook (via lefthook) runs unit tests automatically before every `git push`.

### Port reference

> **⚠️ Two runtime modes — two different API ports**
>
> | Mode | Frontend | API |
> |------|----------|-----|
> | **Docker Compose** (`./run.sh all`) | http://localhost:4200 (`ng serve`) | http://localhost:**8080** |
> | **kind cluster** (`deploy-local.sh`) | http://localhost:**8090** (nginx-ingress) | http://localhost:**8090**/api |
>
> `ng serve` always targets port **8080** (local Spring Boot process).  
> The kind cluster bundles everything behind **8090** — use the kind URL if the app is only running in Kubernetes.

#### Application

| Service | Port | URL |
|---------|------|-----|
| Spring Boot API (local) | 8080 | http://localhost:8080/swagger-ui.html |
| Angular UI (`ng serve`) | 4200 | http://localhost:4200 → API on :8080 |
| kind ingress — frontend + API | 8090 | http://localhost:8090 (HTTPS: 8443) |

#### Data stores

| Service | Port | Notes |
|---------|------|-------|
| PostgreSQL | 5432 | user: `demo` / pass: `demo` |
| Redis | 6379 | |
| Kafka (KRaft) | 9092 | PLAINTEXT\_HOST listener |
| Ollama (LLM) | 11434 | llama3.2:1b — pulled on first start |
| Keycloak | 9090 | admin / admin · realm: `mirador-service` |

#### Admin tools

| Service | Port | URL |
|---------|------|-----|
| pgAdmin | 5050 | http://localhost:5050 (no login) |
| pgweb | 8081 | http://localhost:8081 |
| Kafka UI | 9080 | http://localhost:9080 |
| Redis Commander | 8082 | http://localhost:8082 |
| RedisInsight | 5540 | http://localhost:5540 |
| Maven Site (reports) | 8083 | http://localhost:8083 — run `mvn verify && mvn site` first |

#### Observability

| Service | Port | URL / Notes |
|---------|------|-------------|
| Grafana (standalone) | 3000 | http://localhost:3000 · Prometheus datasource |
| Grafana LGTM | 3001 | http://localhost:3001 · **Tempo + Loki** datasources |
| Tempo Explore | 3001 | http://localhost:3001/explore → select Tempo |
| Tempo HTTP API | 3200 | `GET /api/traces/{traceId}` — direct trace lookup |
| Prometheus | 9091 | http://localhost:9091 (9090 used by Keycloak) |
| Loki (CORS proxy) | 3100 | Nginx proxy adding `Access-Control-Allow-Origin` |
| OTLP HTTP collector | 4318 | Spring Boot sends traces + logs here |
| Pyroscope | 4040 | http://localhost:4040 · CPU/memory flamegraphs |

#### Infrastructure

| Service | Port | Notes |
|---------|------|-------|
| Docker API proxy | 2375 | Filtered read-only Docker Engine API (CORS) |
| GitLab Runner | — | Outbound HTTPS polling — no port exposed |

---

## Screenshots

### Grafana — HTTP metrics
![Grafana Dashboard](docs/screenshots/grafana-overview.png)

### Prometheus — raw metrics
![Prometheus Dashboard](docs/screenshots/prometheus-overview.png)

### Grafana — OpenTelemetry traces
![Grafana OTel Dashboard](docs/screenshots/grafana-otel-overview.png)

---

## Detailed documentation

| Document | Content |
|----------|---------|
| [Architecture](docs/architecture.md) | Component reference, call flows, code organisation |
| [API Reference](docs/api.md) | All endpoints with curl examples |
| [Security](docs/security.md) | OWASP patterns, demo scenarios, headers |
| [Observability](docs/observability.md) | Dashboards, diagnostic scenarios, Kafka patterns, resilience |

---

## Spring Boot & Java compatibility

The default build targets **Spring Boot 4.0.5 + Java 25**. Maven profiles enable compilation
and testing against older versions — no code change required.

### Supported combinations

| Command | Spring Boot | Java | Notes |
|---------|-------------|------|-------|
| `mvn verify` | 4.0.5 | 25 | Default — native API versioning, `ScopedValue`, switch pattern matching |
| `mvn verify -Dcompat` | 4.0.5 | 21 | `ScopedValue` replaced by `ThreadLocal` |
| `mvn verify -Dcompat -Djava17` | 4.0.5 | 17 | + switch pattern matching replaced by if/else |
| `mvn verify -Dsb3` | 3.4.5 | 21 | SB3 BOM + `ThreadLocal` + manual header-based API versioning |
| `mvn verify -Dsb3 -Djava17` | 3.4.5 | 17 | SB3 + Java 17 (all compat layers applied) |

### How it works

Source overlays in dedicated directories replace version-specific files at compile time.
The compiler is pointed at a merged copy — no original file is modified.

| Overlay directory | Replaces | Why |
|-------------------|----------|-----|
| `src/main/java-compat/` | `RequestContext`, `RequestIdFilter`, `TraceService` | `ScopedValue` (Java 25) → `ThreadLocal` (Java 17/21) |
| `src/main/java-compat-java17/` | `ApiExceptionHandler` | switch pattern matching (Java 21) → if/else (Java 17) |
| `src/main/java-sb3/` | `CustomerController` | `@GetMapping(version=...)` (Spring 7) → manual `X-API-Version` header dispatch |
| `src/test/java-sb3/` | `AutoConfigureMockMvc` | Bridge annotation: SB4 package → SB3 package |

The `RestTestClient`-based test (`CustomerRestClientITest`) is excluded from SB3 builds
since that class only exists in Spring Framework 7. The `CustomerApiITest` (MockMvc) covers
the same endpoints.

### Maven compatibility

The project supports both **Maven 3.9.x** (default) and **Maven 4.0.x**.

The Maven Wrapper (`./mvnw`) pins the exact version. To switch:

```bash
# Edit .mvn/wrapper/maven-wrapper.properties and uncomment the desired distributionUrl:
#   Maven 3.9.14 (default)
#   Maven 4.0.0-rc-3

# Then verify:
./mvnw --version
```

**Tested with Maven 4.0.0-rc-3** — all 5 profile combinations compile and pass tests.
All plugin versions are resolved via the `spring-boot-starter-parent` `<pluginManagement>`,
which Maven 4 accepts. No unversioned plugins, no deprecated `<prerequisites>` or
`<reporting>` sections. The `maven-antrun-plugin` conditional copies (`xmlns:if="ant:if"`)
use standard Ant features supported by both Maven versions.

---

## Local Kubernetes (kind)

Spin up a full production-equivalent stack on your machine using
[kind](https://kind.sigs.k8s.io/) (Kubernetes IN Docker). One command deploys
Postgres, Redis, Kafka, the Spring Boot backend, and the Angular frontend.

```bash
# Prerequisites (once)
brew install kind kubectl

# Deploy everything (builds images, creates cluster, applies manifests)
./scripts/deploy-local.sh

# Re-deploy after a code change (skip the image rebuild)
./scripts/deploy-local.sh --skip-build

# Tear down
./scripts/deploy-local.sh --delete
```

| Endpoint | URL |
|----------|-----|
| Frontend | http://localhost:8090 |
| API | http://localhost:8090/api |
| Swagger | http://localhost:8090/api/swagger-ui.html |
| Health | http://localhost:8090/api/actuator/health |

Credentials: `admin/admin` · `user/user` · `viewer/viewer`

> **Note on macOS**: kind defaults to `kindest/node:v1.35.0` which has a kubelet
> startup timeout on Docker Desktop. The config pins `v1.31.4` which is stable.

---

## CI/CD

### GitLab pipeline stages

| Stage | Jobs | Trigger |
|-------|------|---------|
| `lint` | Hadolint (Dockerfile) | Every push |
| `test` | Unit tests, OWASP scan | Every push |
| `integration` | Failsafe ITests (Testcontainers), SpotBugs, JaCoCo | Every push |
| `package` | JAR + Docker image (`--cache-from` for fast rebuilds) | `main` + tags |
| `compat` | 4 SB/Java combos | Manual / `RUN_COMPAT=true` |
| `native` | GraalVM native image | Daily schedule (no variable) |
| `reports` | Maven site + push to `reports/` branch | Daily schedule (`REPORT_PIPELINE=true`) |
| `deploy` | 6 deployment targets (see above) | `main` |

> **Report schedule setup**: in GitLab → CI/CD → Schedules, create a schedule at `0 2 * * *`
> with variable `REPORT_PIPELINE=true` and create a project access token (Reporter role,
> `write_repository` scope) saved as `GITLAB_REPORTS_TOKEN` CI variable.

### Run CI jobs locally (free, no gitlab.com minutes)

```bash
# 1. Start the runner
docker compose -f docker-compose.runner.yml up -d

# 2. Register it (one-time — get the token from gitlab.com → Settings → CI/CD → Runners)
./scripts/register-runner.sh glrt-xxxxxxxxxxxx
```

After registration every push triggers jobs on **your machine** instead of gitlab.com shared runners.

| Pipeline | Config |
|----------|--------|
| GitLab CI | `.gitlab-ci.yml` |
| GitHub Actions | `.github/workflows/ci.yml` |

```bash
./run.sh verify   # local equivalent of the full CI pipeline (no Docker needed)
```
