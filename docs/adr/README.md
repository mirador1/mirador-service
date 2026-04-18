# Architecture Decision Records (ADRs)

This directory captures architectural decisions using the lightweight
**Michael Nygard format**. One ADR per decision — immutable once merged.

## Why ADRs?

- **Non-obvious decisions must be justified and dated.** "Why did we pick
  Kustomize and not Helm?" becomes a Git-tracked answer instead of a Slack
  thread nobody can find 6 months later.
- **Onboarding.** New developers (and new Claude sessions) can read the ADR
  index to understand the constraints shaping the codebase.
- **Reversibility.** An ADR is a point-in-time record. If we reverse a
  decision later, we don't delete the old ADR — we mark it
  `Superseded by ADR-NNNN` and write a new one.

## Format

Every ADR follows the same 5 sections:

1. **Context** — what problem are we solving, what constraints apply
2. **Decision** — what we're doing, concretely
3. **Consequences** — positive, negative, neutral
4. **Alternatives considered** — what we rejected and why
5. **References** — links that informed the decision

File name pattern: `NNNN-kebab-case-title.md` (NNNN is a zero-padded sequence).

## Index

| ID    | Status      | Title                                                               |
| ----- | ----------- | ------------------------------------------------------------------- |
| 0001  | Accepted    | [Record architecture decisions](0001-record-architecture-decisions.md) |
| 0002  | Accepted    | [Kustomize over Helm for K8s manifests](0002-kustomize-over-helm.md)    |
| 0003  | Superseded  | [Cloud SQL over in-cluster Postgres on GKE](0003-cloud-sql-over-in-cluster-postgres.md) — see ADR-0013 |
| 0004  | Accepted    | [Local CI runner, no paid SaaS quota](0004-local-ci-runner.md)          |
| 0005  | Accepted    | [In-cluster Kafka (not Managed) for cost reasons](0005-in-cluster-kafka.md) |
| 0006  | Accepted    | [Hoist every Maven version into `<properties>`](0006-maven-version-hoisting.md) |
| 0007  | Accepted    | [Workload Identity Federation for GCP auth in CI](0007-workload-identity-federation.md) |
| 0008  | Accepted    | [Feature-sliced package layout in `com.mirador.*`](0008-feature-sliced-packages.md) |
| 0009  | Accepted    | [Container runtime base image — `eclipse-temurin:25-jre`](0009-container-runtime-base-image.md) |
| 0010  | Accepted    | [OpenTelemetry OTLP push to a Collector (not Prometheus scrape)](0010-otlp-push-to-collector.md) |
| 0011  | Accepted    | [Minimal `@Transactional` surface, no `@Transactional(readOnly = true)`](0011-transactional-read-strategy.md) |
| 0012  | Accepted    | [Stay on LGTM with Loki bloom filters — defer OpenSearch](0012-stay-on-lgtm-with-bloom-filters.md) |
| 0013  | Accepted    | [In-cluster Postgres on GKE (supersedes ADR-0003)](0013-in-cluster-postgres-on-gke-for-the-demo.md) |
| 0014  | Accepted    | [Single-replica deployments for the demo cluster](0014-single-replica-for-demo.md) |
| 0015  | Accepted    | [Argo CD for GitOps deployment on GKE](0015-argocd-for-gitops-deployment.md) |
| 0016  | Accepted    | [External Secrets Operator + Google Secret Manager](0016-external-secrets-operator.md) |
| 0017  | Accepted    | [Java 25 + Spring Boot 4 (bleeding-edge stack)](0017-jvm-25-spring-boot-4-strategy.md) |
| 0018  | Accepted    | [JWT strategy — HMAC + single-use refresh + Redis blacklist](0018-jwt-strategy-hmac-refresh-rotation.md) |
| 0019  | Accepted    | [Resilience4J (CB+Retry) + Bucket4J rate limit + idempotency filter](0019-resilience4j-circuitbreaker-retry-bucket4j.md) |
| 0020  | Accepted    | [API versioning via `X-API-Version` header (Spring 7)](0020-api-versioning-via-header.md) |
| 0021  | Accepted    | [Cost-deferred industrial patterns](0021-cost-deferred-industrial-patterns.md) |

## When to write an ADR

Write an ADR when the decision:

- Changes the public API or contract with another system
- Introduces a new tool, service, or dependency at the infrastructure level
- Replaces one approach with another (Helm → Kustomize, Java 21 → 25)
- Defines a pattern others are expected to follow (security, error handling)
- Locks in a constraint that limits future choices

Do **not** write an ADR for:

- Code style choices (that's what Prettier/Checkstyle are for)
- One-off bug fixes
- Library upgrades within a major version

## Template

Copy [`0000-template.md`](0000-template.md) and fill in the sections.
