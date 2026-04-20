# Architecture Decision Records (ADRs)

This directory captures architectural decisions using the lightweight
**Michael Nygard format**. One ADR per decision — immutable once merged.

The format stays strictly standard (flat numbering, immutable files,
`Superseded by ADR-NNNN` when reversed). What's added on top is a
**hierarchical index by theme** below the flat index — purely
navigational, no file layout change.

## Why ADRs?

- **Non-obvious decisions must be justified and dated.** "Why did we
  pick Kustomize and not Helm?" becomes a git-tracked answer instead
  of a Slack thread no one can find 6 months later.
- **Onboarding.** New developers (and new Claude sessions) can read
  the ADR index to understand the constraints shaping the codebase.
- **Reversibility.** An ADR is a point-in-time record. If we reverse
  a decision later, we don't delete the old one — we mark it
  `Superseded by ADR-NNNN` and write a new one.

## Status snapshot

- **Accepted**: 35
- **Superseded**: 1 (ADR-0003 → ADR-0013)
- **Deprecated**: 0

## Format

Every ADR follows the same 5 sections:

1. **Context** — what problem we're solving, what constraints apply
2. **Decision** — what we're doing, concretely
3. **Consequences** — positive, negative, neutral
4. **Alternatives considered** — what we rejected and why
5. **References** — links that informed the decision

File name pattern: `NNNN-kebab-case-title.md`. Numbering is
monotonic across supersessions.

## Flat index (by chronological number)

| ID | Status | Title |
|---|---|---|
| 0001 | Accepted | [Record architecture decisions](0001-record-architecture-decisions.md) |
| 0002 | Accepted | [Kustomize over Helm for K8s manifests](0002-kustomize-over-helm.md) |
| 0003 | Superseded | [Cloud SQL over in-cluster Postgres on GKE](0003-cloud-sql-over-in-cluster-postgres.md) → [ADR-0013](0013-in-cluster-postgres-on-gke-for-the-demo.md) |
| 0004 | Accepted | [Local CI runner, no paid SaaS quota](0004-local-ci-runner.md) |
| 0005 | Accepted | [In-cluster Kafka (not Managed) for cost reasons](0005-in-cluster-kafka.md) |
| 0006 | Accepted | [Hoist every Maven version into `<properties>`](0006-maven-version-hoisting.md) |
| 0007 | Accepted | [Workload Identity Federation for GCP auth in CI](0007-workload-identity-federation.md) |
| 0008 | Accepted | [Feature-sliced package layout in `com.mirador.*`](0008-feature-sliced-packages.md) |
| 0009 | Accepted | [Container runtime base image — `eclipse-temurin:25-jre`](0009-container-runtime-base-image.md) |
| 0010 | Accepted | [OpenTelemetry OTLP push to a Collector (not Prometheus scrape)](0010-otlp-push-to-collector.md) |
| 0011 | Accepted | [Minimal `@Transactional` surface](0011-transactional-read-strategy.md) |
| 0012 | Accepted | [Stay on LGTM with Loki bloom filters — defer OpenSearch](0012-stay-on-lgtm-with-bloom-filters.md) |
| 0013 | Accepted | [In-cluster Postgres on GKE (supersedes ADR-0003)](0013-in-cluster-postgres-on-gke-for-the-demo.md) |
| 0014 | Accepted | [Single-replica deployments for the demo cluster](0014-single-replica-for-demo.md) |
| 0015 | Accepted | [Argo CD for GitOps deployment on GKE](0015-argocd-for-gitops-deployment.md) |
| 0016 | Accepted | [External Secrets Operator + Google Secret Manager](0016-external-secrets-operator.md) |
| 0017 | Accepted | [Java 25 + Spring Boot 4 (bleeding-edge stack)](0017-jvm-25-spring-boot-4-strategy.md) |
| 0018 | Accepted | [JWT strategy — HMAC + single-use refresh + Redis blacklist](0018-jwt-strategy-hmac-refresh-rotation.md) |
| 0019 | Accepted | [Resilience4J (CB+Retry) + Bucket4J + idempotency filter](0019-resilience4j-circuitbreaker-retry-bucket4j.md) |
| 0020 | Accepted | [API versioning via `X-API-Version` header (Spring 7)](0020-api-versioning-via-header.md) |
| 0021 | Accepted | [Cost-deferred industrial patterns](0021-cost-deferred-industrial-patterns.md) |
| 0022 | Accepted | [Ephemeral demo cluster (bring up on demand)](0022-ephemeral-demo-cluster.md) |
| 0023 | Accepted | [Stay on GKE Autopilot (over GKE Standard)](0023-stay-on-autopilot.md) |
| 0024 | Accepted | [BFF observability proxy + Unleash without SDK](0024-bff-observability-proxy-and-unleash-without-sdk.md) |
| 0025 | Accepted | [UI local-only, no public prod ingress](0025-ui-local-only-no-public-prod-ingress.md) |
| 0026 | Accepted | [Spring Boot scope limit — no third-party tool awareness](0026-spring-boot-scope-limit-no-third-party-tool-awareness.md) |
| 0027 | Accepted | [Decline service mesh for the portfolio demo](0027-decline-service-mesh-for-portfolio-demo.md) |
| 0028 | Accepted | [kind cluster in CI for manifest validation](0028-kind-cluster-in-ci-for-manifest-validation.md) |
| 0029 | Accepted | [Jenkinsfile parity demonstrator + declarative linter](0029-jenkinsfile-parity-and-declarative-linter.md) |
| 0030 | Accepted | [Choose GCP (GKE) as the Kubernetes target](0030-choose-gcp-as-the-kubernetes-target.md) |
| 0031 | Accepted | [Version adoption policy — patch / minor / major](0031-version-adoption-policy.md) |
| 0032 | Accepted | [Community standards + hierarchical ADR index](0032-community-standards-and-hierarchical-adr-index.md) |
| 0033 | Accepted | [Playwright E2E in kind-in-CI](0033-playwright-e2e-in-kind-in-ci.md) |
| 0034 | Accepted | [CI memory budget + Testcontainers-heavy ITs](0034-ci-memory-budget-testcontainers.md) |
| 0035 | Accepted | [Defer Pact + Biome adoption](0035-defer-pact-and-biome.md) |
| 0036 | Accepted | [Multi-cloud Terraform posture](0036-multi-cloud-terraform-posture.md) |

## Hierarchical index (by theme)

The same ADRs, grouped to show how they relate. An ADR can legitimately
belong to two themes — it's listed under its **primary** one here to
avoid duplication. Cross-references inside each ADR file remain
authoritative.

### 🧭 Meta

- [ADR-0001](0001-record-architecture-decisions.md) — Record architecture decisions
- [ADR-0032](0032-community-standards-and-hierarchical-adr-index.md) — Community standards + hierarchical index

### 🏗️ Architecture & code patterns

- [ADR-0006](0006-maven-version-hoisting.md) — Hoist Maven versions into `<properties>`
- [ADR-0008](0008-feature-sliced-packages.md) — Feature-sliced package layout
- [ADR-0011](0011-transactional-read-strategy.md) — Minimal `@Transactional` surface
- [ADR-0017](0017-jvm-25-spring-boot-4-strategy.md) — Java 25 + Spring Boot 4
- [ADR-0020](0020-api-versioning-via-header.md) — API versioning via `X-API-Version`
- [ADR-0026](0026-spring-boot-scope-limit-no-third-party-tool-awareness.md) — Spring Boot scope limit

### 📨 Messaging & data

- [ADR-0003](0003-cloud-sql-over-in-cluster-postgres.md) — _(superseded)_ Cloud SQL over in-cluster Postgres
- [ADR-0005](0005-in-cluster-kafka.md) — In-cluster Kafka for cost
- [ADR-0013](0013-in-cluster-postgres-on-gke-for-the-demo.md) — In-cluster Postgres on GKE

### 🔁 CI / release

- [ADR-0004](0004-local-ci-runner.md) — Local CI runner, zero SaaS quota
- [ADR-0028](0028-kind-cluster-in-ci-for-manifest-validation.md) — kind cluster in CI
- [ADR-0029](0029-jenkinsfile-parity-and-declarative-linter.md) — Jenkinsfile parity demonstrator + linter
- [ADR-0031](0031-version-adoption-policy.md) — Version adoption policy
- [ADR-0033](0033-playwright-e2e-in-kind-in-ci.md) — Playwright E2E in kind-in-CI
- [ADR-0034](0034-ci-memory-budget-testcontainers.md) — CI memory budget + Testcontainers-heavy ITs
- [ADR-0035](0035-defer-pact-and-biome.md) — Defer Pact + Biome adoption (deferred trigger conditions)

### ☸️ Kubernetes & deployment

- [ADR-0002](0002-kustomize-over-helm.md) — Kustomize over Helm
- [ADR-0009](0009-container-runtime-base-image.md) — Container runtime base image
- [ADR-0014](0014-single-replica-for-demo.md) — Single-replica for demo
- [ADR-0015](0015-argocd-for-gitops-deployment.md) — Argo CD for GitOps

### 💰 Cost & cluster lifecycle

- [ADR-0021](0021-cost-deferred-industrial-patterns.md) — Cost-deferred patterns
- [ADR-0022](0022-ephemeral-demo-cluster.md) — Ephemeral demo cluster
- [ADR-0023](0023-stay-on-autopilot.md) — Stay on GKE Autopilot
- [ADR-0025](0025-ui-local-only-no-public-prod-ingress.md) — UI local-only, no public ingress
- [ADR-0027](0027-decline-service-mesh-for-portfolio-demo.md) — Decline service mesh
- [ADR-0030](0030-choose-gcp-as-the-kubernetes-target.md) — Choose GCP (GKE)
- [ADR-0036](0036-multi-cloud-terraform-posture.md) — Multi-cloud Terraform posture (reference modules)

### 🔐 Secrets & authentication

- [ADR-0007](0007-workload-identity-federation.md) — Workload Identity Federation
- [ADR-0016](0016-external-secrets-operator.md) — External Secrets Operator + GSM
- [ADR-0018](0018-jwt-strategy-hmac-refresh-rotation.md) — JWT HMAC + refresh rotation

### 📡 Observability & telemetry

- [ADR-0010](0010-otlp-push-to-collector.md) — OTLP push to collector
- [ADR-0012](0012-stay-on-lgtm-with-bloom-filters.md) — Stay on LGTM with bloom filters
- [ADR-0024](0024-bff-observability-proxy-and-unleash-without-sdk.md) — BFF observability proxy + Unleash

### 🛡️ Resilience

- [ADR-0019](0019-resilience4j-circuitbreaker-retry-bucket4j.md) — CB + Retry + rate limit + idempotency

## Cross-theme references

ADRs often touch multiple themes. The list below flags the 5 most
frequently cross-linked ones — worth reading first for a broad
understanding:

1. **[ADR-0022](0022-ephemeral-demo-cluster.md)** — the €2/month
   budget anchor that influences every subsequent cost decision.
2. **[ADR-0026](0026-spring-boot-scope-limit-no-third-party-tool-awareness.md)** —
   the "app doesn't know about third-party tools" rule that shaped
   ADR-0024 (BFF retirement) and the UI-side ADR-0008 (observability
   page retirement).
3. **[ADR-0017](0017-jvm-25-spring-boot-4-strategy.md)** — the
   bleeding-edge vs stable trade-off anchoring ADR-0031 (version
   adoption) and the compat matrix.
4. **[ADR-0021](0021-cost-deferred-industrial-patterns.md)** — the
   editorial rule that decides which "nice-to-have" features land.
5. **[ADR-0029](0029-jenkinsfile-parity-and-declarative-linter.md)** —
   the canonical-vs-parity CI rule that produced
   `docs/ops/ci-philosophy.md`.

## When to write an ADR

Write an ADR when the decision:

- Changes the public API or contract with another system
- Introduces a new tool, service, or dependency at the infrastructure
  level
- Replaces one approach with another (Helm → Kustomize, Java 21 → 25)
- Defines a pattern others are expected to follow (security, error
  handling)
- Locks in a constraint that limits future choices

Do **not** write an ADR for:

- Code-style choices (that's what Prettier/Checkstyle are for)
- One-off bug fixes
- Library upgrades within a major version (unless the major itself
  is an ADR — ADR-0017 for Java 25)

## Template

Copy [`0000-template.md`](0000-template.md) and fill in the sections.

## Maintenance rule

When a new ADR lands, the **author** updates two places in the same MR:

1. The flat index table (above) — new row
2. The appropriate hierarchical section — new bullet

A reviewer who spots an ADR missing from either is entitled to block
the MR on that. Keeping the nav in sync is cheaper than regenerating
it from scratch each quarter.
