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

- **Accepted**: 44
- **Superseded**: 3 (ADR-0003 → ADR-0013; ADR-0008 → ADR-0044; ADR-0037 → Path B embedded)
- **Deprecated**: 0
- **Reserved (never drafted)**: 0045, 0046 — consolidated into 0041/0042
  before acceptance (see commit
  [`33e31e5`](https://gitlab.com/mirador1/mirador-service/-/commit/33e31e5)).
  The numbers are intentionally skipped in the flat index ; don't
  re-use them for new ADRs.

### Supersession graph

How historical decisions evolved into the current set. Each arrow
reads "was superseded by" — the arrow-head carries the reason the
reversal happened.

```mermaid
flowchart LR
    A0003[ADR-0003<br/><b>Cloud SQL over in-cluster Postgres</b><br/><i>2026-04 · superseded</i>]
    A0013[ADR-0013<br/><b>In-cluster Postgres on GKE</b><br/><i>2026-04 · accepted</i>]
    A0008[ADR-0008<br/><b>Feature-sliced package layout</b><br/><i>2026-04 · superseded</i>]
    A0044[ADR-0044<br/><b>Hexagonal considered, feature-slicing retained</b><br/><i>2026-04 · accepted</i>]
    A0037[ADR-0037 v1<br/><b>Spectral oas3-valid-* rules disabled</b><br/><i>temporary shield</i>]
    A0037b[ADR-0037 v2<br/><b>OpenApiSchemaSanitizer bean (Path B)</b><br/><i>same doc, Path B addendum</i>]

    A0003 -->|"€2/mo cost-control<br/>ADR-0022 wins"| A0013
    A0008 -->|"port/ sub-package<br/>for outbound events"| A0044
    A0037 -->|"root cause fixed<br/>(schema sanitizer)<br/>rules re-enabled"| A0037b

    classDef superseded fill:#f3f3f3,stroke:#999,stroke-dasharray:5,color:#666
    classDef current fill:#e8f5e9,stroke:#2e7d32,color:#1b5e20
    class A0003,A0008,A0037 superseded
    class A0013,A0044,A0037b current
```

**Reading the graph:** dashed boxes = historical decisions kept for
audit trail (per Michael Nygard's rule "immutable once merged").
Solid-green boxes = the current, authoritative decisions. The arrow
label captures the SHORT reason the reversal happened — full context
in the superseding ADR's **Context** section.

**Pattern to preserve:** when superseding an ADR, keep the old file
with `Status: Superseded by ADR-NNNN`, write the new decision as a
fresh ADR that references the old one in its `Supersedes:` header.
Never edit the old file to change its decision — only the Status
line is mutable.

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

> The table below is **auto-regenerated** from the ADR files by
> `bin/dev/regen-adr-index.sh`. Do not edit by hand — changes are
> overwritten on the next CI pass. `bin/dev/stability-check.sh` reports
> drift when the table no longer matches the ADR files.

<!-- ADR-INDEX:START -->
| ID | Status | Title |
|---|---|---|
| 0001 | Accepted | [Record architecture decisions](0001-record-architecture-decisions.md) |
| 0002 | Accepted | [Kustomize over Helm for Kubernetes manifests](0002-kustomize-over-helm.md) |
| 0003 | Superseded | [Cloud SQL over in-cluster Postgres on GKE](0003-cloud-sql-over-in-cluster-postgres.md) → [ADR-0013](0013-in-cluster-postgres-on-gke-for-the-demo.md) |
| 0004 | Accepted | [Local CI runner, no paid SaaS quota](0004-local-ci-runner.md) |
| 0005 | Accepted | [In-cluster Kafka (not Managed) for cost reasons](0005-in-cluster-kafka.md) |
| 0006 | Accepted | [Hoist every Maven version into `<properties>`](0006-maven-version-hoisting.md) |
| 0007 | Accepted | [Workload Identity Federation for GCP auth in CI](0007-workload-identity-federation.md) |
| 0008 | Superseded | [Feature-sliced package layout in `com.mirador.*`](0008-feature-sliced-packages.md) → [ADR-0044](0044-hexagonal-considered-feature-slicing-retained.md) |
| 0009 | Accepted | [Container runtime base image — `eclipse-temurin:25-jre`](0009-container-runtime-base-image.md) |
| 0010 | Moved | [moved to `mirador-service-shared`](0010-otlp-push-to-collector.md) |
| 0011 | Accepted | [Minimal `@Transactional` surface, no `@Transactional(readOnly = true)`](0011-transactional-read-strategy.md) |
| 0012 | Accepted | [Stay on LGTM with Loki bloom filters — defer OpenSearch](0012-stay-on-lgtm-with-bloom-filters.md) |
| 0013 | Accepted | [In-cluster Postgres on GKE (revisits ADR-0003)](0013-in-cluster-postgres-on-gke-for-the-demo.md) |
| 0014 | Accepted | [Single-replica deployments for the demo cluster](0014-single-replica-for-demo.md) |
| 0015 | Accepted | [Argo CD for GitOps deployment on GKE](0015-argocd-for-gitops-deployment.md) |
| 0016 | Moved | [moved to `mirador-service-shared`](0016-external-secrets-operator.md) |
| 0017 | Accepted | [Java 25 + Spring Boot 4 (bleeding-edge stack)](0017-jvm-25-spring-boot-4-strategy.md) |
| 0018 | Accepted | [JWT strategy — HMAC access tokens + single-use refresh rotation + Redis blacklist](0018-jwt-strategy-hmac-refresh-rotation.md) |
| 0019 | Accepted | [Resilience4J (CB + Retry) + Bucket4J rate-limit + idempotency filter](0019-resilience4j-circuitbreaker-retry-bucket4j.md) |
| 0020 | Accepted | [API versioning via `X-API-Version` header (Spring Framework 7)](0020-api-versioning-via-header.md) |
| 0021 | Moved | [moved to `mirador-service-shared`](0021-cost-deferred-industrial-patterns.md) |
| 0022 | Accepted | [Ephemeral demo cluster (bring up on demand)](0022-ephemeral-demo-cluster.md) |
| 0023 | Accepted | [Stay on GKE Autopilot (over GKE Standard)](0023-stay-on-autopilot.md) |
| 0024 | Accepted | [BFF pattern for observability + Unleash without the SDK](0024-bff-observability-proxy-and-unleash-without-sdk.md) |
| 0025 | Accepted | [One UI, run locally; no public ingress in prod; port-forward tunnels for prod access](0025-ui-local-only-no-public-prod-ingress.md) |
| 0026 | Accepted | [Spring Boot scope = app domain + self-admin only; no awareness of third-party tools](0026-spring-boot-scope-limit-no-third-party-tool-awareness.md) |
| 0027 | Accepted | [Decline service mesh (Istio / Linkerd) for the portfolio demo](0027-decline-service-mesh-for-portfolio-demo.md) |
| 0028 | Accepted | [kind-in-CI for K8s manifest validation before GKE](0028-kind-cluster-in-ci-for-manifest-validation.md) |
| 0029 | Accepted | [Jenkinsfile parity demonstrator + declarative-linter validation](0029-jenkinsfile-parity-and-declarative-linter.md) |
| 0030 | Accepted | [Choose GCP (GKE) as the Kubernetes target](0030-choose-gcp-as-the-kubernetes-target.md) |
| 0031 | Accepted | [Version adoption policy (patch / minor / major)](0031-version-adoption-policy.md) |
| 0032 | Accepted | [Community standards files + hierarchical ADR index](0032-community-standards-and-hierarchical-adr-index.md) |
| 0033 | Accepted | [Playwright E2E in kind-in-CI](0033-playwright-e2e-in-kind-in-ci.md) |
| 0034 | Accepted | [CI memory budget + Testcontainers-heavy integration tests](0034-ci-memory-budget-testcontainers.md) |
| 0035 | Accepted | [Defer Pact + Biome adoption (proposals #5 + #17)](0035-defer-pact-and-biome.md) |
| 0036 | Moved | [moved to `mirador-service-shared`](0036-multi-cloud-terraform-posture.md) |
| 0037 | Superseded | [Spectral `oas3-valid-*-example` rules disabled (temporary)](0037-spectral-oas3-valid-example-rules-disabled.md) |
| 0038 | Accepted | [Cluster metrics via OTel Collector receivers in lgtm, not kube-prometheus-stack](0038-kubeletstats-receiver-in-lgtm-not-kube-prometheus-stack.md) |
| 0039 | Moved | [moved to `mirador-service-shared`](0039-two-observability-deployment-modes.md) |
| 0040 | Accepted | [Accept `insecureSkipVerify: true` on GKE kubelet ServiceMonitor](0040-accept-insecureskipverify-on-gke-kubelet-scrape.md) |
| 0041 | Accepted | [CI hygiene: honest green discipline](0041-ci-hygiene-honest-green-discipline.md) |
| 0042 | Accepted | [Quality reports routing: SonarCloud vs Maven Site](0042-quality-reports-routing-sonarcloud-vs-maven-site.md) |
| 0043 | Accepted | [Pin GitHub Actions by full commit SHA (not tag)](0043-pin-github-actions-by-commit-sha.md) |
| 0044 | Accepted | [Hexagonal considered, feature-slicing retained (with ports-and-adapters lite on outbound events)](0044-hexagonal-considered-feature-slicing-retained.md) |
| 0045 | Superseded | [Superseded (consolidated into ADR-0041..0044)](0045-superseded-consolidated-into-0041-0044.md) |
| 0046 | Skip | [Numéro non utilisé (skip de numérotation)](0046-skipped-numbering-only.md) |
| 0047 | Accepted | [Auth0 consent screen stays for social logins (Google OAuth2)](0047-auth0-consent-for-social-logins.md) |
| 0048 | Amended | [Mirador alert rules evaluate in Prometheus but don't route via Alertmanager](0048-prometheus-alert-rules-evaluate-but-dont-route.md) |
| 0049 | Accepted | [CI shields (`allow_failure: true`) require a dated exit ticket](0049-ci-shields-with-dated-exit-tickets.md) |
| 0050 | Accepted | [CI YAML modularisation — `ci/includes/*.yml` per concern](0050-ci-yaml-modularisation-plan.md) |
| 0051 | Accepted | [JPA entity = domain model (accept the coupling)](0051-jpa-entity-as-domain-model.md) |
| 0052 | Accepted | [Backend stays ignorant of build/quality tools (tightens ADR-0026)](0052-backend-not-coupled-to-build-tools.md) |
| 0053 | Accepted | [OVH Cloud as 2nd canonical Kubernetes target](0053-ovh-canonical-target.md) |
| 0054 | Moved | [moved to `mirador-service-shared`](0054-gitlab-observability-dual-export.md) |
| 0055 | Moved | [moved to `mirador-common`](0055-shell-based-release-automation.md) |
| 0056 | Accepted | [Widget extraction pattern for large Angular components](0056-widget-extraction-pattern.md) |
| 0057 | Moved | [moved to `mirador-common`](0057-polyrepo-vs-monorepo.md) |
| 0058 | Accepted | [Phase C : Checkstyle `failOnViolation=true`](0058-phase-c-checkstyle-flip.md) |
| 0059 | Accepted | [Grafana Cloud AI Observability (POC opt-in)](0059-grafana-cloud-ai-observability-poc.md) |
| 0060 | Accepted | [SB3 compat target = prod-grade, not informational](0060-sb3-compat-prod-grade.md) |
| 0061 | Living | [SB3 / SB4 incompatibility catalog](0061-sb3-sb4-incompatibility-catalog.md) |
| 0062 | Proposed | [MCP server : `@Tool` per-method on the service layer](0062-mcp-server-tool-exposure-per-method.md) |
<!-- ADR-INDEX:END -->

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
- [ADR-0008](0008-feature-sliced-packages.md) — _(superseded)_ Feature-sliced package layout → [ADR-0044](0044-hexagonal-considered-feature-slicing-retained.md)
- [ADR-0011](0011-transactional-read-strategy.md) — Minimal `@Transactional` surface
- [ADR-0017](0017-jvm-25-spring-boot-4-strategy.md) — Java 25 + Spring Boot 4
- [ADR-0020](0020-api-versioning-via-header.md) — API versioning via `X-API-Version`
- [ADR-0026](0026-spring-boot-scope-limit-no-third-party-tool-awareness.md) — Spring Boot scope limit
- [ADR-0044](0044-hexagonal-considered-feature-slicing-retained.md) — Hexagonal considered, feature-slicing retained (+ `customer/port/` sub-package convention)
- [ADR-0051](0051-jpa-entity-as-domain-model.md) — JPA entity = domain model (accept the coupling, document the invariants)

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
- [ADR-0041](0041-ci-hygiene-honest-green-discipline.md) — CI hygiene: honest green (scope-out over shield, tag on post-merge main green)
- [ADR-0042](0042-quality-reports-routing-sonarcloud-vs-maven-site.md) — Quality reports routing (ESLint, Pitest, Trivy, Semgrep, Spectral)
- [ADR-0043](0043-pin-github-actions-by-commit-sha.md) — Pin GitHub Actions by commit SHA

### ☸️ Kubernetes & deployment

- [ADR-0002](0002-kustomize-over-helm.md) — Kustomize over Helm
- [ADR-0009](0009-container-runtime-base-image.md) — Container runtime base image
- [ADR-0014](0014-single-replica-for-demo.md) — Single-replica for demo
- [ADR-0015](0015-argocd-for-gitops-deployment.md) — Argo CD for GitOps
- [ADR-0040](0040-accept-insecureskipverify-on-gke-kubelet-scrape.md) — Accept `insecureSkipVerify: true` on GKE kubelet scrape

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
- [ADR-0047](0047-auth0-consent-for-social-logins.md) — Auth0 consent screen stays for social logins

### 📡 Observability & telemetry

- [ADR-0010](0010-otlp-push-to-collector.md) — OTLP push to collector
- [ADR-0012](0012-stay-on-lgtm-with-bloom-filters.md) — Stay on LGTM with bloom filters
- [ADR-0024](0024-bff-observability-proxy-and-unleash-without-sdk.md) — BFF observability proxy + Unleash
- [ADR-0038](0038-kubeletstats-receiver-in-lgtm-not-kube-prometheus-stack.md) — Cluster metrics via OTel Collector receivers in lgtm (no kube-prom-stack)
- [ADR-0039](0039-two-observability-deployment-modes.md) — Two overlays: OTel-native (`local`) vs Prometheus-community (`local-prom`)

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
