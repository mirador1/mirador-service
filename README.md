![Mirador Service](docs/assets/banner.svg)

<sub>**English** · [Français](README.fr.md)</sub>

<!-- Build / release status. GitLab badges first — canonical CI.
     GitHub badges (CodeQL, Scorecard) render only on the GitHub mirror. -->
[![pipeline](https://gitlab.com/mirador1/mirador-service/badges/main/pipeline.svg)](https://gitlab.com/mirador1/mirador-service/-/pipelines)
[![coverage](https://gitlab.com/mirador1/mirador-service/badges/main/coverage.svg)](https://gitlab.com/mirador1/mirador-service/-/pipelines)
[![latest release](https://gitlab.com/mirador1/mirador-service/-/badges/release.svg)](https://gitlab.com/mirador1/mirador-service/-/releases)
[![CodeQL](https://github.com/mirador1/mirador-service/actions/workflows/codeql.yml/badge.svg)](https://github.com/mirador1/mirador-service/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/mirador1/mirador-service/badge)](https://scorecard.dev/viewer/?uri=github.com/mirador1/mirador-service)

<!-- Tech badges — grouped by concern so the README reflects the ADR story,
     not just a technology dump. Each group corresponds to an ADR or a
     docs/architecture/*.md page. Bumping the list here should mirror
     docs/reference/technologies.md. -->

**Runtime**
![Java 25](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot 4](https://img.shields.io/badge/Spring_Boot-4-6DB33F?logo=springio&logoColor=white)
![PostgreSQL 17](https://img.shields.io/badge/PostgreSQL-17-4169E1?logo=postgresql&logoColor=white)
![Apache Kafka](https://img.shields.io/badge/Apache_Kafka-KRaft-231F20?logo=apachekafka&logoColor=white)
![Redis 7](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)
![Angular 21](https://img.shields.io/badge/Angular-21_zoneless-DD0031?logo=angular&logoColor=white)

**Platform**
![Docker](https://img.shields.io/badge/Docker-compose_+_buildx-2496ED?logo=docker&logoColor=white)
![Kubernetes](https://img.shields.io/badge/Kubernetes-GKE_Autopilot_+_kind-326CE5?logo=kubernetes&logoColor=white)
![Terraform](https://img.shields.io/badge/Terraform-GKE_+_GSM-844FBA?logo=terraform&logoColor=white)
![Argo CD](https://img.shields.io/badge/Argo_CD-GitOps-EF7B4D?logo=argo&logoColor=white)
![Argo Rollouts](https://img.shields.io/badge/Argo_Rollouts-canary-EF7B4D?logo=argo&logoColor=white)
![External Secrets](https://img.shields.io/badge/External_Secrets-GSM-326CE5?logo=kubernetes&logoColor=white)
![cert-manager](https://img.shields.io/badge/cert--manager-Let's_Encrypt-326CE5?logo=kubernetes&logoColor=white)
![Unleash](https://img.shields.io/badge/Unleash-feature_flags-000000)

**Observability**
![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-traces_+_logs_+_metrics-7F52FF?logo=opentelemetry&logoColor=white)
![Grafana LGTM](https://img.shields.io/badge/Grafana-LGTM-F46800?logo=grafana&logoColor=white)
![Tempo](https://img.shields.io/badge/Tempo-traces-F46800?logo=grafana&logoColor=white)
![Loki](https://img.shields.io/badge/Loki-logs-F46800?logo=grafana&logoColor=white)
![Mimir](https://img.shields.io/badge/Mimir-Prom_API-F46800?logo=grafana&logoColor=white)
![Pyroscope](https://img.shields.io/badge/Pyroscope-continuous_profiling-F46800?logo=grafana&logoColor=white)

**Security & supply chain**
![Auth0 + Keycloak](https://img.shields.io/badge/Auth0_+_Keycloak-OIDC-EB5424?logo=auth0&logoColor=white)
![Resilience4j](https://img.shields.io/badge/Resilience4j-CB_+_retry_+_bulkhead-1B5E20)
![Bucket4j](https://img.shields.io/badge/Bucket4j-rate_limit-4A90E2)
![Kyverno](https://img.shields.io/badge/Kyverno-policies-326CE5?logo=kubernetes&logoColor=white)
![cosign + SBOM](https://img.shields.io/badge/cosign_+_SBOM-supply_chain-2D7FF9)
![Trivy / Grype / Syft](https://img.shields.io/badge/Trivy_Grype_Syft-image_scan-1904DA?logo=aquasecurity&logoColor=white)
![Semgrep](https://img.shields.io/badge/Semgrep-SAST-1E1E2E?logo=semgrep&logoColor=white)
![OWASP Dep-Check](https://img.shields.io/badge/OWASP_Dep--Check-CVE_scan-000000?logo=owasp&logoColor=white)

**Quality**
![SonarCloud](https://img.shields.io/badge/SonarCloud-static_analysis-F3702A?logo=sonarcloud&logoColor=white)
![PIT mutation](https://img.shields.io/badge/PIT-mutation_tests-4CAF50)
![JaCoCo](https://img.shields.io/badge/JaCoCo-unit_+_IT_coverage-8CBF26)
![Testcontainers](https://img.shields.io/badge/Testcontainers-k8s_+_JVM-2496ED?logo=docker&logoColor=white)
![Vitest](https://img.shields.io/badge/Vitest-UI_unit-6E9F18?logo=vitest&logoColor=white)
![Playwright](https://img.shields.io/badge/Playwright-E2E_kind--in--CI-2EAD33?logo=playwright&logoColor=white)
![k6](https://img.shields.io/badge/k6-load_tests-7D64FF?logo=k6&logoColor=white)
![Chaos Mesh](https://img.shields.io/badge/Chaos_Mesh-NetworkChaos-FF6B35?logo=kubernetes&logoColor=white)

**CI / release**
![GitLab CI](https://img.shields.io/badge/GitLab_CI-canonical-FC6D26?logo=gitlab&logoColor=white)
![Jenkinsfile](https://img.shields.io/badge/Jenkinsfile-parity-D24939?logo=jenkins&logoColor=white)
![Renovate](https://img.shields.io/badge/Renovate-bump_bot-1A1F6C?logo=renovatebot&logoColor=white)
![release-please](https://img.shields.io/badge/release--please-CHANGELOG_+_semver-4285F4)
![lefthook](https://img.shields.io/badge/lefthook-pre--push_gates-000000)
![commitlint](https://img.shields.io/badge/Conventional_Commits-enforced-FE5196)
![gitleaks](https://img.shields.io/badge/gitleaks-secret_scan-FD7014)

# Mirador — the watchtower for a real running system

> **Watch. Understand. Act.**
>
> _Built with the right tools and the right methods._

**Mirador** — Spanish for *watchtower* — is a vantage point. The project
picks a concrete Customer API backend and stands watch over it from every
angle at once: **the code, the runtime metrics, the CI/CD pipelines, and
the industry-standard tooling wired around it**. Everything you see in the
paired UI ([`mirador-ui`](https://gitlab.com/mirador1/mirador-ui)) and in
Grafana is the same live system observed from two windows.

This repository is the **Spring Boot 4 / Java 25 backend**. It is the one
being watched.

What the project actually exercises:

- **Reference-grade industrial tooling**: GitLab CI with local runner, Kustomize-over-Helm
  K8s manifests, OpenTelemetry (traces + logs + metrics) to Grafana Cloud, Sonar,
  Semgrep, Trivy / Grype / Syft / cosign / Dockle, OWASP Dependency-Check, PIT mutation
  testing, resilience4j circuit-breakers + bucket4j rate limiting, Flyway, Testcontainers,
  Workload Identity Federation, release-please. Each is justified in an ADR under
  [`docs/adr/`](docs/adr/) or in the glossary at [`docs/reference/technologies.md`](docs/reference/technologies.md).
- **Live observability of a running system**: every layer (JVM, HTTP, DB pool, Kafka,
  Redis, Tomcat, business counters) emits metrics and traces so the accompanying UI
  (and Grafana) can show what the code and the runtime are actually doing.
- **AI-assisted integration work**: the selection, wiring, and documentation of most
  of this tooling — the ADRs, the technology glossary, the CI hardening, the K8s
  baseline, the observability setup — were produced in close collaboration with an
  LLM, and the same technique keeps the docs, tests, and configuration in sync as
  the system grows.

The original demo scenario ("what does it take to diagnose an incident?") is still
the organising principle — the stack is built around that use case rather than
around the technologies themselves.

## Table of contents

- [Why this, not that — the arbitrages](#why-this-not-that--the-arbitrages)
- [Simplification levers](#simplification-levers)
- [AI-assisted integration — where it contributed, where it didn't](#ai-assisted-integration--where-it-contributed-where-it-didnt)
- [Known limitations](#known-limitations)
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

## Why this, not that — the arbitrages

Every industrial pattern in this repo answers a concrete problem; the
list below is what I **rejected** and why. The full set of decisions
with context + alternatives + consequences lives under
[`docs/adr/`](docs/adr/) — 23 ADRs at the time of writing.

| Decision | What I picked | What I considered & why it lost |
|---|---|---|
| **Message bus** | Apache Kafka (KRaft, in-cluster) | **RabbitMQ** — simpler but doesn't demo log-structured retention for event replay. **Managed Kafka on GCP** — €1k/month, disproportionate for a demo (see [ADR-0005](docs/adr/0005-in-cluster-kafka.md)). |
| **K8s packaging** | Kustomize overlays (`local`/`gke`/`eks`/`aks`) | **Helm** — great for distributed charts but the demo has a single chart; Kustomize wins on "no templating-language debugging" (see [ADR-0002](docs/adr/0002-kustomize-over-helm.md)). |
| **Database (GKE overlay)** | In-cluster Postgres StatefulSet | **Cloud SQL** — started there, reverted after realising PITR / backups / Query Insights aren't in the demo scope (see [ADR-0003 superseded → ADR-0013](docs/adr/0013-in-cluster-postgres-on-gke-for-the-demo.md)). |
| **Secret management** | External Secrets Operator + Google Secret Manager | **HashiCorp Vault** — more powerful but too much platform for 5 secrets. **Sealed Secrets** — still puts secrets in git. **CI-created K8s Secret** (the original) — no rotation story, CI gets write access to cluster (see [ADR-0016](docs/adr/0016-external-secrets-operator.md)). |
| **GitOps** | Argo CD (core subset: server + app-controller + repo-server + redis) | **Flux v2** — lighter but no UI. **ApplicationSet + Dex + Notifications** — dropped because the demo has one app (see [ADR-0015](docs/adr/0015-argocd-for-gitops-deployment.md)). |
| **JWT strategy** | HS256 + opaque refresh tokens in Postgres + Redis blacklist | **RS256 + JWKS** — needed for the Keycloak path, not for the built-in one. **Stateless refresh JWTs** — would still need a revocation list, so opaque + single-use is simpler (see [ADR-0018](docs/adr/0018-jwt-strategy-hmac-refresh-rotation.md)). |
| **Observability ingestion** | OTLP push to a collector (LGTM in-cluster) | **Prometheus scrape** — pull-based needs node access to every pod, fiddly on Autopilot. **Direct Grafana Cloud** — fine but costs money once out of the free tier (see [ADR-0010](docs/adr/0010-otlp-push-to-collector.md)). |
| **CI runner** | Local MacBook Autopilot (m1) | **SaaS minutes** — runs out of the 400-free-minutes tier in two days. **Self-hosted on GKE** — chicken-and-egg if the CI builds the cluster (see [ADR-0004](docs/adr/0004-local-ci-runner.md)). |
| **Cluster cost** | Ephemeral Autopilot (up only during demos) | **GKE Standard 1 × e2-small always-on** — €30/month vs €2/month for a cluster that doesn't serve traffic 99 % of the time (see [ADR-0022](docs/adr/0022-ephemeral-demo-cluster.md)). |

Guiding principle: if a technology was picked, it should be possible to
articulate why a specific alternative was *rejected*. A rejection
reason that doesn't exist is a warning that the choice wasn't made
deliberately.

---

## Simplification levers

If the stack had to shrink without losing the core demonstration,
here is the order items would come out, from lowest cost (biggest win
per LOC removed) to highest:

1. **Keycloak.** The built-in JWT auth covers the demo scenario. Keycloak
   exists only to exercise the OIDC-via-JWKS path — valuable to show the
   *capability*, but the first thing to go if the stack must shrink to
   "stuff that serves traffic". The JwtAuthenticationFilter already
   gracefully degrades when Keycloak is absent.
2. **Kafka.** Customer creation, update, and delete all work without a
   message bus. Kafka is there to exercise two patterns (fire-and-forget
   + request-reply), which are nice-to-have, not core. The whole
   `com.mirador.messaging` package could be deleted and the app would
   still pass 80 % of the tests.
3. **Ollama + Spring AI.** The `/customers/{id}/bio` endpoint is a
   showcase for circuit-breaker + retry + fallback behaviour — those
   same patterns are exercised on the JSONPlaceholder HTTP integration,
   which is simpler. Ollama is the most expensive dependency to run
   (1–8 GB RAM, 1 CPU, or GPU).
4. **The second API version (v2).** `@RequestMapping(version = "2.0+")`
   is a Spring 7 feature I wanted to demonstrate — it adds duplicate
   controller methods and tests. Removing v2 halves the controller code
   with no loss of business value.
5. **Three of the four Kubernetes overlays.** `local`, `gke`, `eks`,
   `aks` are mostly the same manifest with a different TLS + storage
   class patch. For a real single-cloud deployment I would keep one.

Kept regardless of pressure, with the reason each earns its place:
- Observability (OTel, structured logs) — without it every production
  incident becomes detective work from log timestamps.
- The CI supply-chain tooling (SBOM, Grype, cosign) — ~30 s runtime
  and catches real CVEs; removing it removes an invariant.
- The ADR set — a decision log that costs nothing to maintain and
  prevents the same trade-offs being relitigated later.

---

## AI-assisted integration — where it contributed, where it didn't

The project was built in close collaboration with a reasoning LLM —
specifically **Anthropic's [Claude Opus 4.7](https://www.anthropic.com/claude)**
(1 M-token context window), driven from the
[Claude Code](https://docs.anthropic.com/claude/docs/claude-code) CLI.
Each commit's `Co-Authored-By:` trailer names the exact model
responsible, so the git log doubles as an audit trail of where the
assistant contributed.

The split between what came from the model and what came from a human
review is worth being explicit about, because it changes how each
part should be read.

**Division of labour, in one sentence**:

> The assistant enumerates options; the arbitrage — which option fits
> this specific context and which get rejected — is a human call, and
> the ADRs under [`docs/adr/`](docs/adr/) are its audit trail.

The technology proposals come from a system that has read a large
corpus of platform-engineering post-mortems and can enumerate options
faster than a human. Enumeration is cheap. Choosing is not.

**Areas where AI provided high leverage with low verification cost**:
- ADRs drafted from a bullet-point briefing — consistent
  context/decision/alternatives/consequences structure produced in
  minutes.
- Boilerplate YAML (NetworkPolicies, Ingresses, SecretStore CRs) from
  a one-line intent description, then line-by-line review.
- Class refactors matching a new pattern (JSpecify annotations, the
  underscore pattern for unused catches, pattern matching for switch)
  — mechanical work with clear acceptance criteria.
- Commit messages and MR descriptions drafted from the diff.

**Areas where the first LLM output was wrong and had to be corrected**:
- Cost estimate in ADR-0021. The initial "€0–3/month" for the GKE
  Autopilot cluster was off by two orders of magnitude once the
  actual pod-hour billing was measured (~€190/month), which led to
  ADR-0022 (ephemeral cluster pattern, ~€2/month actual).
- Spring AI shim removal. An early suggestion that Spring AI 1.1 GA
  no longer needed the SB3-package compatibility classes turned out
  to be wrong in CI — the shims remain load-bearing.
- NetworkPolicy for DNS. The first draft allowed `kube-system` egress;
  GKE Autopilot routes DNS through NodeLocal DNS Cache at
  `169.254.20.10`, which required reading `/etc/resolv.conf` on an
  actual pod to discover.

**Decisions that remained human, with the assistant providing inputs**:
- Scope. Every "add X" proposal was filtered against "does this solve
  a concrete problem the demo exercises?" (ADR-0021 + ADR-0022
  editorial rule).
- Arbitrages in the table above. The assistant can list alternatives;
  selecting one and documenting why the others lost is a judgement
  call that belongs in the ADRs.
- Items deliberately left out — the nice-to-have section of ADR-0022
  records what was considered and rejected.

---

## Known limitations

The items below are caveats that a live session will surface anyway.
Documenting them up front is cheaper than discovering them mid-demo,
and also clarifies which limitations are deliberate trade-offs
(linked to an ADR) rather than unintentional gaps.

- **Cold start is slow** — a fresh `bin/cluster/demo-up.sh` takes ~8 min
  (cluster provisioning 5 min + operator installs 2 min + app sync
  1 min). Access needs one more step: `bin/cluster/pf-prod.sh` to open local
  tunnels to every service (ADR-0025). I warm the cluster up 10 min
  before any live walkthrough and leave `pf-prod.sh --daemon` running
  in the background.
- **`/actuator/health` shows DOWN when an upstream is down** — and the
  demo often runs without Ollama (it's optional; the CircuitBreaker
  handles the absence). This is intended but surprises viewers: the
  readiness probe rejects traffic even though the core API works.
- **The public-tag semantics of `:stable` are weak** — Argo CD tracks
  `main` HEAD, which is what a fresh demo uses, but there is no
  guarantee the HEAD image has been k6-smoke-tested. A proper setup
  would pin to a signed release tag.
- **Single replicas everywhere** — if the JVM pod OOMs mid-demo
  there's a 30-60 s outage while Spring Boot warms up. See
  [ADR-0014](docs/adr/0014-single-replica-for-demo.md) for the
  scale-up playbook.
- **No chaos engineering run in CI** — Chaos Mesh is installed and the
  UI has a "chaos" page, but the experiments are run interactively,
  not as part of a pipeline. A real production setup would schedule
  weekly chaos experiments with SLO gates.
- **Pipeline times are not tiny** — the full `mvn verify` takes ~4 min;
  the docker-build stage adds 2-3 min (Kaniko, arm64 → amd64 buildx).
  Fast enough to be tolerable, slow enough that I try to keep PRs
  small so the feedback loop doesn't drag.
- **The technology glossary drifts** — `docs/reference/technologies.md`
  is 1100+ lines and some entries describe the intent rather than the
  current implementation. A doc-diff job in CI would catch this; I
  haven't written it yet.

If a manager asks "where are the compromises?" this section is the
honest answer. None of them are blockers for the demo, all of them are
known.

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

When deployed to a Kubernetes cluster the backend is reachable **only via
`kubectl port-forward`** — no public Ingress, no TLS, no DNS (ADR-0025).
The Angular UI is never deployed in the cluster; it runs on the developer
laptop against the tunnelled cluster endpoints.

```
Developer laptop                         GKE Autopilot (no public surface)
                                         ─────────────────────────────────
  Angular UI (localhost:4200)            namespace: app
        │                                  mirador-service:8080   (Spring Boot 4)
   EnvService selects "Prod tunnel"        HPA 1–5, PDB 1-min-available
        │
        ▼                                namespace: infra
  kubectl port-forward  ══════════════►    PostgreSQL 17 (StatefulSet)
  (bin/cluster/pf-prod.sh — prod = +20000)         Redis 7 / Kafka / Keycloak / Unleash
        │                                  LGTM all-in-one (Grafana + Loki + Tempo + Mimir)
        ▼
  localhost:28080 → mirador
  localhost:23000 → grafana
  localhost:24242 → unleash
  localhost:28081 → argo-cd
  localhost:25432 → postgres (CloudBeaver)
  … (see bin/cluster/pf-prod.sh or docs/architecture/environments-and-flows.md)
```

> **Why no public URL**: ADR-0025 trades recruiter click-through for
> zero-attack-surface. CloudBeaver on localhost talks to the tunnelled
> Postgres; Grafana iframe talks to the tunnelled LGTM. Same UI code
> against dev (compose) or prod (tunnels) — only the port numbers differ.

**CI deployment targets** (deploy stage in `.gitlab-ci.yml`):

| Target | Trigger |
|--------|---------|
| GKE Autopilot | Auto on `main` push |
| AWS EKS | Manual |
| Azure AKS | Manual |
| Google Cloud Run | Manual (serverless) |
| Fly.io | Manual (PaaS) |
| k3s / bare metal | Manual |

> **Terraform for non-GCP clouds exists as reference.** `deploy/terraform/`
> ships four modules — `gcp/` is the canonical target (applied in CI),
> while `aws/` (ECS Fargate), `azure/` (AKS), and `scaleway/` (Kapsule,
> EU-sovereign) are reference implementations kept for portability review.
> See [ADR-0036](docs/adr/0036-multi-cloud-terraform-posture.md) for the
> "ready-to-review, not-ready-to-apply" posture and
> [`deploy/terraform/README.md`](deploy/terraform/README.md) for the
> when-to-pick-which guide + cost comparison.

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
docker compose up -d              # core only: DB + Kafka + Redis + app (~1 GB, ~4 containers)
./run.sh obs                      # observability (Grafana, Prometheus, Tempo, Zipkin, Pyroscope)
./run.sh app                      # Spring Boot app
```

#### Compose profiles

The compose stack is profile-gated so a fresh clone doesn't pull ~12 GB
of optional tooling on the first `docker compose up`. Profiles are
additive — combine them as needed.

| Profile | Services | When to activate |
|---|---|---|
| (none) | `db`, `kafka`, `redis`, `app` | Default. Minimum to boot the API. |
| `full` | + `keycloak`, `ollama` | OAuth2 IdP + local LLM (Spring AI). Heavy — ~3 GB extra. |
| `admin` | + `cloudbeaver`, `pgweb-local`, `kafka-ui`, `redisinsight`, `redis-commander`, `sonarqube` | Browsing & quality UIs (SQL, topics, Redis, static analysis). |
| `docs` | + `maven-site`, `compodoc` | Local static-site servers for Maven reports + Angular Compodoc. |
| `observability` (in `deploy/compose/observability.yml`) | `lgtm`, `cors-proxy`, `docker-proxy` | Grafana + Loki + Tempo + Mimir + Pyroscope + CORS/Docker proxies. |
| `kind-tunnel` / `prod-tunnel` | `pgweb-kind` / `pgweb-prod` | Browse kind / GKE Postgres via `bin/cluster/pf-*.sh` port-forwards. |

```bash
# Examples
docker compose up -d                                       # core only
docker compose --profile full up -d                        # core + keycloak + ollama
docker compose --profile admin up -d                       # core + browsing tools
docker compose --profile full --profile admin up -d        # "kitchen sink"
docker compose -f docker-compose.yml \
               -f deploy/compose/observability.yml \
               --profile full --profile admin --profile observability up -d
```

`./run.sh all` activates `full + admin + observability` to preserve the
historical "start everything" behaviour.

### Quick API smoke test

```bash
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

> **Three runtime modes, UI always on `:4200`. Backend port changes per
> environment — compose uses upstream, kind adds +10000, prod +20000.**
>
> | Mode | Launcher | Backend API |
> |------|----------|-------------|
> | **Docker Compose (dev)** | `./run.sh all` | `http://localhost:8080` |
> | **kind cluster** | `scripts/deploy-local.sh` + `bin/cluster/pf-kind.sh` | `http://localhost:18080` |
> | **GKE (prod)** | `bin/cluster/demo-up.sh` + `bin/cluster/pf-prod.sh` | `http://localhost:28080` |
>
> Cluster modes go through `kubectl port-forward` (ADR-0025) — the UI's
> EnvService picks between the three. Full port map in
> `docs/architecture/environments-and-flows.md`.

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
![Grafana Dashboard](docs/assets/screenshots/grafana-overview.png)

### Prometheus — raw metrics
![Prometheus Dashboard](docs/assets/screenshots/prometheus-overview.png)

### Grafana — OpenTelemetry traces
![Grafana OTel Dashboard](docs/assets/screenshots/grafana-otel-overview.png)

---

## Detailed documentation

### Topic guides (`docs/`)

| Document | Audience | Content |
|----------|----------|---------|
| [Dev tooling](docs/getting-started/dev-tooling.md) | Every new dev | OpenLens / Docker Desktop / VS Code / IntelliJ setup, GitLab auth, environment-by-environment connect recipes |
| [Environments & flows](docs/architecture/environments-and-flows.md) | New contributors + reviewers | Two ASCII diagrams (compose + GKE via tunnels) + per-page call table + architectural invariants |
| [Architecture](docs/architecture/overview.md) | New contributors | Component reference, call flows, code organisation |
| [API Reference](docs/api/api.md) | API consumers | All endpoints with curl examples |
| [API Contract](docs/api/contract.md) | API consumers | Versioning policy (`X-API-Version` vs URL), deprecation rules, BC guarantees |
| [Security](docs/architecture/security.md) | Security reviewers | OWASP patterns, threat model, auth flows, CVE handling |
| [Observability](docs/architecture/observability.md) | SRE / ops | Dashboards, trace/log/metric flow, diagnostic scenarios, Kafka, resilience, Grafana Cloud |

### Architecture decisions (ADRs)

Non-obvious choices are justified in Michael-Nygard–style ADRs under
[`docs/adr/`](docs/adr/README.md):

- [0001 — Record architecture decisions](docs/adr/0001-record-architecture-decisions.md)
- [0002 — Kustomize over Helm for K8s manifests](docs/adr/0002-kustomize-over-helm.md)
- [0003 — Cloud SQL over in-cluster Postgres on GKE](docs/adr/0003-cloud-sql-over-in-cluster-postgres.md)
- [0004 — Local CI runner, no paid SaaS quota](docs/adr/0004-local-ci-runner.md)
- [0005 — In-cluster Kafka (not Managed) for cost reasons](docs/adr/0005-in-cluster-kafka.md)
- [0006 — Hoist every Maven version into `<properties>`](docs/adr/0006-maven-version-hoisting.md)
- [0007 — Workload Identity Federation for GCP auth in CI](docs/adr/0007-workload-identity-federation.md)
- [0008 — Feature-sliced package layout in `com.mirador.*`](docs/adr/0008-feature-sliced-packages.md)
- [0009 — Container runtime base image — `eclipse-temurin:25-jre`](docs/adr/0009-container-runtime-base-image.md)
- [0010 — OpenTelemetry OTLP push to a Collector (not Prometheus scrape)](docs/adr/0010-otlp-push-to-collector.md)
- [0011 — Minimal `@Transactional` surface, no `@Transactional(readOnly = true)`](docs/adr/0011-transactional-read-strategy.md)
- [0012 — Stay on LGTM with Loki bloom filters — defer OpenSearch](docs/adr/0012-stay-on-lgtm-with-bloom-filters.md)

### Folder-level orientation (`README.md` in each directory)

| Folder | README points at |
|--------|------------------|
| [`infra/`](infra/README.md) | Local Docker Compose mount configs (Keycloak, nginx, observability, pgAdmin, Postgres) |
| [`infra/keycloak/`](infra/keycloak/README.md) | Realm imports for dev and prod |
| [`infra/nginx/`](infra/nginx/README.md) | Compodoc + Maven-site reverse proxies |
| [`infra/observability/`](infra/observability/README.md) | LGTM stack + OTel collector + CORS proxy |
| [`infra/postgres/`](infra/postgres/README.md) | One-shot SQL init scripts (SonarQube DB, etc.) |
| [`deploy/`](deploy/README.md) | Production deployment artefacts (Terraform + Kubernetes) |
| [`deploy/kubernetes/`](deploy/kubernetes/README.md) | K8s manifests per target (backend/frontend/stateful/gke/local) |
| [`deploy/terraform/`](deploy/terraform/README.md) | IaC entry point — GCP (canonical) + AWS / Azure / Scaleway reference modules (ADR-0036). Picks which. |
| [`deploy/terraform/gcp/`](deploy/terraform/gcp/README.md) | File-by-file walkthrough of the **canonical** GCP module (applied in CI). |
| [`deploy/terraform/aws/`](deploy/terraform/aws/README.md) | **Reference** — AWS ECS Fargate (no EKS — control-plane fee rules it out of €10/month cap). |
| [`deploy/terraform/azure/`](deploy/terraform/azure/README.md) | **Reference** — Azure AKS (Standard_B2s, free control plane). |
| [`deploy/terraform/scaleway/`](deploy/terraform/scaleway/README.md) | **Reference** — Scaleway Kapsule (EU-sovereign, cheapest always-on at ~€10/month). |
| [`config/`](config/README.md) | Static analyzer configs (OWASP, PMD, SpotBugs) |
| [`scripts/`](scripts/README.md) | Dev scripts (deploy-local, simulate-traffic, register-runner) |
| [`build/`](build/owasp-data-README.md) | Build-time templates (OWASP README generator) |
| [`src/main/resources/`](src/main/resources/README.md) | Classpath layout (application.yml, Flyway, logback, cached CI reports) |
| [`src/site/`](src/site/README.md) | Maven site descriptor (may be deprecated — see note) |

### Auto-generated API docs

- **Javadoc** (via `mvn site`) — `target/site/apidocs/` when built locally.
- **OpenAPI / Swagger UI** — served at `/swagger-ui.html` when the app is running.
- **Angular API reference (Compodoc)** — in the [`mirador-ui`](https://gitlab.com/mirador1/mirador-ui) repo, reachable at <http://localhost:8085> via the local `compodoc` nginx container.

### Task tracking

- `TASKS.md` — pending work backlog when present (source of truth across
  sessions); deleted when empty per `CLAUDE.md` rule.
- [`CLAUDE.md`](CLAUDE.md) — project-specific instructions for Claude Code sessions.

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
docker compose -f deploy/compose/runner.yml up -d

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

---

## Code Quality

This project uses a layered quality stack: static analysis, test coverage, mutation testing, dependency CVE scanning, and cloud-based code intelligence.

All tools are integrated into the CI/CD pipeline and results are aggregated in the Angular dashboard at **Settings → Code Report** (route `/quality`).

### Tool overview

| Tool | What it checks | When it runs | Report |
|------|---------------|--------------|--------|
| **JaCoCo** | Line + branch test coverage (gate: 70%) | Every push | `/actuator/quality` → Coverage tab · Maven site |
| **SpotBugs** | Bytecode bugs: null deref, threading, correctness | Every push | `/actuator/quality` → Bugs tab · GitLab MR annotations |
| **PMD** | Code smells: unused vars, duplicates, complexity | `mvn verify -Preport,report-static -Dcompat` | `/actuator/quality` → PMD tab |
| **Checkstyle** | Style: Google Java Style Guide | `mvn verify -Preport,report-static -Dcompat` | `/actuator/quality` → Checkstyle tab |
| **PIT (Pitest)** | Mutation testing — measures test strength | `mvn verify -Preport` | `/actuator/quality` → Pitest tab |
| **OWASP Dep-Check** | CVE scan on all Maven dependencies | Every push (2h timeout) | `/actuator/quality` → OWASP tab |
| **SonarCloud** | Comprehensive analysis: bugs, smells, hotspots, duplication | Every push to `main` / MR | [sonarcloud.io ↗](https://sonarcloud.io/project/overview?id=mirador1_mirador-service) |
| **GitLab Code Quality** | SpotBugs + PMD + Checkstyle as inline MR diff annotations | Every push to `main` / MR | MR → Code Quality widget |
| **Semgrep** | OSS rules: Java bugs, Spring patterns, OWASP Top 10, secrets | Daily schedule + manual | CI artifact `semgrep-report.json` · GitLab Security Dashboard |
| **Maven Site** | HTML report portal: Surefire + JaCoCo + SpotBugs + Javadoc | Daily schedule | `reports/` branch · http://localhost:8084 |
| **Trivy** | Docker image OS + Java CVE scan | Every push to `main` | CI artifact `trivy-report.json` |

### Run quality checks locally

```bash
# Fast path — unit tests + SpotBugs + JaCoCo (Java 25, default)
./mvnw verify

# Full report — adds OWASP CVE scan + Pitest mutation coverage (takes ~20 min)
./mvnw verify -Preport

# Static analysis — adds PMD + Checkstyle (requires Java 21 — both crash on Java 25)
./mvnw verify -Preport,report-static -Dcompat

# Generate HTML site (Surefire, JaCoCo, SpotBugs, Javadoc) → target/site/
./mvnw site

# Serve the site locally (nginx on port 8084)
docker compose up -d maven-site   # then open http://localhost:8084

# Mutation testing only (skips all other analysis)
./mvnw test-compile pitest:mutationCoverage -Preport

# SonarCloud (requires SONAR_TOKEN)
./mvnw verify sonar:sonar -Dsonar.token=$SONAR_TOKEN -Dsonar.host.url=https://sonarcloud.io

# Semgrep (requires Docker — no account needed)
docker run --rm -v $(pwd):/src semgrep/semgrep \
  semgrep --config=p/java --config=p/spring --config=p/owasp-top-ten \
  --json --output=/src/semgrep-report.json --exclude="src/test" src/main/java/
```

### SonarCloud setup (one-time)

> **Free for public repositories** at [sonarcloud.io](https://sonarcloud.io).

1. Log in at sonarcloud.io with your GitLab account
2. Import the `mirador1/mirador-service` project
3. Generate a token at sonarcloud.io → Account → Security
4. Add `SONAR_TOKEN` to GitLab → Settings → CI/CD → Variables (masked + protected)

The `sonar.organization` and `sonar.projectKey` are already set in `pom.xml`.

### Semgrep

No setup required — rulesets are fetched from the public Semgrep registry at runtime. The `semgrep` CI job runs on the daily report schedule (`REPORT_PIPELINE=true`) or via manual trigger. Results appear in the GitLab Security Dashboard (SAST widget) and as `semgrep-report.json` in the pipeline artifacts.

### GitLab Code Quality widget

SpotBugs + PMD + Checkstyle findings are converted to the [GitLab Code Quality](https://docs.gitlab.com/ee/ci/testing/code_quality.html) format by the `code-quality` CI job and appear as inline annotations on changed lines in every MR. No setup required.

### Live quality dashboard

The backend exposes all quality data (tests, coverage, bugs, sonar, OWASP, pitest, build info, GitLab pipelines) via `/actuator/quality`. The Angular UI reads this endpoint and displays it at **Settings → Code Report** (`/quality` route).

The main dashboard also shows a compact quality summary (tests, coverage %, SpotBugs bugs, Sonar rating) in the **Code Quality** section.
