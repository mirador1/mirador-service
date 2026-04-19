# Methods and techniques — mirador

Companion document to
[`technologies.md`](technologies.md). That file lists **tools**
(libraries, frameworks, services). This file lists **methods and
techniques** — patterns, practices, methodologies — and says for
each one what it is, where it's documented canonically, and why
Mirador uses it.

Hierarchically organised by concern. Every entry has four fields:
- **Name** — canonical spelling
- **Reference** — the authoritative URL (book chapter, seminal paper, vendor doc)
- **What it does** — 1–2 sentences, non-marketing
- **Why here** — the specific justification on this project

Bloat warning: if an entry's "Why here" boils down to "because it's
standard", the entry should be deleted. Only techniques that earn
their place survive.

---

## 1. Development & Design

### 1.1 Architectural patterns

#### Layered architecture
- **Reference**: [Martin Fowler — Layered architecture](https://martinfowler.com/bliki/PresentationDomainDataLayering.html)
- **What it does**: Separates controller / service / repository so that each
  layer depends only on the one below.
- **Why here**: Spring Boot's default shape. Mirador stays conventional on
  purpose — the value is in the observability wiring, not in architectural
  exotism (see ADR-0021 editorial rule).

#### API versioning via header
- **Reference**: [IETF RFC 9110 §15.3](https://www.rfc-editor.org/rfc/rfc9110#name-accept)
  + [Spring 7 `@RequestMapping(version = …)`](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-mapping.html)
- **What it does**: Selects controller method based on an `Accept` header
  carrying a version, instead of path segments like `/v1/customers`.
- **Why here**: ADR-0020. Shows that breaking API changes don't require URL
  duplication; makes the API's versioning contract readable from the
  controller code alone.

### 1.2 Testing techniques

#### Test pyramid
- **Reference**: [Martin Fowler — Test pyramid](https://martinfowler.com/articles/practical-test-pyramid.html)
- **What it does**: Many unit tests, fewer integration tests, very few E2E
  tests. The shape keeps feedback fast.
- **Why here**: Measured on every CI run — unit ~2 s, integration ~3 min,
  E2E (future Playwright) ~30 s for 3 scenarios. The mutation layer (PIT)
  sits above unit tests but below integration by cost.

#### Integration testing with Testcontainers
- **Reference**: [Testcontainers manifesto](https://testcontainers.com/guides/introducing-testcontainers/)
- **What it does**: Starts real Docker containers (Postgres, Kafka, Redis)
  for each integration test run, so the code-under-test faces real
  protocols instead of mocks.
- **Why here**: Mirador tests Postgres row-level behaviour, Kafka reply
  timeouts, Redis idempotency — all three have mock-only alternatives
  that lie about edge cases. See
  [ADR on integration testing strategy](../adr/).

#### Mutation testing
- **Reference**: [PIT mutation testing docs](https://pitest.org/)
- **What it does**: Rewrites bytecode at random (flip `>` to `<=`, etc.)
  and runs the test suite — if tests still pass, the test is weak.
- **Why here**: Unit tests pass != unit tests are strong. PIT's score
  (`target/pit-reports/`) is the real quality gate for business logic.

#### Chaos testing
- **Reference**: [Netflix's Chaos Engineering principles](https://principlesofchaos.org/)
- **What it does**: Deliberately breaks parts of the system (network
  delay, pod kill, CPU pressure) to verify observability + resilience.
- **Why here**: Chaos Mesh CRs under `deploy/kubernetes/base/chaos/`
  demonstrate the patterns live. The UI's Chaos page wires one-click
  triggers for demo storytelling.

#### Contract testing — declined
- **Reference**: [Pact](https://pact.io) / [Spring Cloud Contract](https://spring.io/projects/spring-cloud-contract)
- **Why declined for now**: Mirador has one consumer (mirador-ui) and
  the two repos ship paired releases. Contract testing earns its place
  when N>1 consumers with independent release cycles. Kept on ROADMAP
  under "nice-to-have".

---

## 2. CI/CD

### 2.1 Branching & merging

#### Trunk-based with dev→main squash
- **Reference**: [trunkbaseddevelopment.com](https://trunkbaseddevelopment.com)
- **What it does**: All work lands on `main` via a single working branch
  (`dev`). Squash-merge keeps `main` linear, one commit per MR.
- **Why here**: Two reasons. (1) Simplified linear history makes
  `release-please` changelogs clean. (2) `bin/ship.sh` workflow depends
  on squash so each MR becomes one commit on main (ADR-0029
  ci-philosophy Rule 1).

#### Conventional Commits
- **Reference**: [conventionalcommits.org](https://www.conventionalcommits.org)
- **What it does**: Standardised commit subjects like
  `feat(scope): subject`. Each type/scope drives automated SemVer +
  CHANGELOG.
- **Why here**: Enforced by a lefthook commit-msg hook. Feeds
  release-please which generates tags + release notes without human
  classification. Free quality signal, zero ongoing cost.

### 2.2 Release management

#### Semantic Versioning
- **Reference**: [semver.org](https://semver.org)
- **What it does**: `MAJOR.MINOR.PATCH` with documented rules for when
  each digit bumps.
- **Why here**: release-please reads Conventional Commits and bumps
  the right digit automatically. Majors still require a human MR review.

#### release-please
- **Reference**: [release-please GitHub](https://github.com/googleapis/release-please)
- **What it does**: Bot that opens a "Release PR" with CHANGELOG +
  version bump; merging the PR creates the tag + GitHub/GitLab release.
- **Why here**: Removes the "write the CHANGELOG, remember to tag"
  manual step that drifts in every team. Paired tag cadence between
  mirador-service and mirador-ui.

### 2.3 Pipeline composition

#### Pipeline-as-Code
- **Reference**: [Jenkins Pipeline book](https://www.jenkins.io/doc/book/pipeline/)
  / [GitLab CI YAML reference](https://docs.gitlab.com/ee/ci/yaml/)
- **What it does**: CI stages + jobs are versioned in the repo alongside
  the code they build, not in a UI.
- **Why here**: `.gitlab-ci.yml` (~1900 lines) is reviewable. A broken
  pipeline traces back to a specific commit. The `Jenkinsfile` proves
  the same philosophy transposes across CI platforms (ADR-0029).

#### Local CI runner
- **Reference**: [GitLab Runner docs](https://docs.gitlab.com/runner/)
- **What it does**: Runs CI jobs on a self-hosted agent instead of
  cloud minutes.
- **Why here**: ADR-0004 — consumes 0 GitLab SaaS minutes. The local
  MacBook runner has full Docker access, uses the local Maven cache
  (3× faster than SaaS fresh), and doesn't leak build artefacts.

#### kind-in-CI manifest validation
- **Reference**: [kind.sigs.k8s.io](https://kind.sigs.k8s.io/)
- **What it does**: Spins up a throwaway Kubernetes cluster inside CI
  to `kubectl apply` the project's manifests and assert pods become
  Ready.
- **Why here**: ADR-0028. Catches manifest drift (missing CRDs, bad
  PodSecurity, broken NetworkPolicies) before production. Local
  feedback in 3 min, vs the 13-min round-trip of a GKE push-deploy.

#### Pre-commit hooks
- **Reference**: [lefthook docs](https://github.com/evilmartians/lefthook)
- **What it does**: Runs cheap checks (prettier, conventional-commits,
  gitlab-ci-lint, readme-i18n-sync) before the commit lands.
- **Why here**: Every check it runs costs <5 s locally vs 15 min of
  pipeline failure. Pays for itself on the first typo caught.

### 2.4 Parity & portability

#### Mirror — canonical + read-only
- **Reference**: `docs/ops/ci-philosophy.md`
- **What it does**: One source of truth (GitLab), read-only mirror
  (GitHub). Mirror push happens in CI on every main push.
- **Why here**: Recruiters read GitHub; canonical work happens on
  GitLab. Rule 1/2/3 in ci-philosophy guard against duplicate CI.

#### Parity demonstrator (Jenkinsfile)
- **Reference**: `docs/ops/jenkins.md`, ADR-0029
- **What it does**: A checked-in Jenkinsfile that would run the same
  stages under Jenkins if an adopting team needed it — not executed
  in our CI.
- **Why here**: Demonstrates the tooling is portable (Testcontainers,
  cosign, PIT, Sonar aren't GitLab-specific). Adoptable in one day
  by a Jenkins shop.

---

## 3. Deployment & Ops

### 3.1 Cluster lifecycle patterns

#### Ephemeral cluster
- **Reference**: [The ephemeral cluster pattern](https://kubernetes.io/blog/2021/04/20/annual-report-summary-2020/)
  / ADR-0022
- **What it does**: Cluster is created by `terraform apply` on demand,
  destroyed after use. State survives in external storage (GCS bucket,
  Secret Manager).
- **Why here**: €2/month instead of €190 for an always-on GKE Autopilot.
  Demonstrates that "prod" doesn't have to mean "24/7 running".

#### Auto-destroy on budget overrun
- **Reference**: `docs/ops/cost-control.md`
- **What it does**: GCP billing alert → Pub/Sub → Cloud Function →
  `gcloud container clusters delete`. Cluster is killed at 100 % of
  the €10/month cap.
- **Why here**: The ephemeral pattern relies on discipline; this closes
  the loop with automation. If the cluster is forgotten running, the
  budget kill-switch enforces the ADR-0022 budget.

### 3.2 Deployment patterns

#### GitOps (pull-based)
- **Reference**: [OpenGitOps principles](https://opengitops.dev/)
- **What it does**: A controller inside the cluster polls the repo for
  desired state and reconciles. No CI pushes to the cluster.
- **Why here**: Argo CD eliminates the "CI has cluster write access"
  attack surface. Any drift from repo is reconciled or reported.
  ADR-0015.

#### Canary rollout (replica-count)
- **Reference**: [Argo Rollouts canary docs](https://argoproj.github.io/argo-rollouts/features/canary/)
- **What it does**: New version goes to N % of replicas, waits,
  promotes or rolls back based on metrics.
- **Why here**: Demonstrates progressive delivery without the service-
  mesh weight (ADR-0027 declined Istio). AnalysisTemplate pattern
  tracked as Tier-1 ROADMAP item.

#### Blue-green — declined
- **Why declined**: Canary covers our use case. Blue-green doubles
  infrastructure cost during cutover — contradicts ADR-0022 budget.

### 3.3 Infrastructure-as-Code

#### Terraform
- **Reference**: [terraform.io](https://www.terraform.io)
- **What it does**: Declarative infra — GKE cluster, Cloud SQL, buckets,
  IAM bindings, all in `deploy/terraform/gcp/`.
- **Why here**: Any team member can recreate the entire GCP footprint
  with one `terraform apply`. No click-ops in the GCP console.

#### Kustomize overlays
- **Reference**: [kustomize.io](https://kustomize.io)
- **What it does**: Base K8s manifests + per-environment patches
  (`overlays/local`, `overlays/gke`, etc.) without a templating
  language.
- **Why here**: ADR-0002 vs Helm. Mirador has one chart; Kustomize's
  "no debug a Go-template string" win is decisive at this size.

### 3.4 Secret management

#### External Secrets Operator
- **Reference**: [external-secrets.io](https://external-secrets.io/)
- **What it does**: In-cluster operator that projects secrets from an
  external store (GCP Secret Manager here) into K8s Secrets.
- **Why here**: ADR-0016. Secrets never land in git. Rotation in GSM
  propagates to pods within the sync interval (1 h default, forced
  via annotation on demand).

#### Workload Identity Federation
- **Reference**: [GCP WIF docs](https://cloud.google.com/iam/docs/workload-identity-federation)
- **What it does**: CI authenticates to GCP via OIDC token exchange —
  no long-lived service-account keys on disk.
- **Why here**: Eliminates the "JSON key in a CI variable that leaks"
  failure mode. Tokens are short-lived, minted per job.

---

## 4. Observability

### 4.1 Signals

#### Golden Signals (SRE)
- **Reference**: [Google SRE Book — Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/)
- **What it does**: Four metrics cover most incidents: latency, traffic,
  errors, saturation.
- **Why here**: Mirador's Grafana home dashboard is organised around
  these four, not around implementation-specific metrics.

#### RED & USE methodologies
- **Reference**: [Tom Wilkie — RED method](https://thenewstack.io/monitoring-microservices-red-method/)
  / [Brendan Gregg — USE method](https://www.brendangregg.com/usemethod.html)
- **What it does**: RED (Rate, Errors, Duration) for services; USE
  (Utilisation, Saturation, Errors) for resources.
- **Why here**: Structures the Golden Signals dashboard panels without
  over-promising per-service custom metrics.

### 4.2 Tracing

#### Distributed tracing via OTLP
- **Reference**: [OpenTelemetry OTLP spec](https://opentelemetry.io/docs/specs/otlp/)
- **What it does**: Traces propagate across service boundaries via
  W3C `traceparent` header, exported in OTLP protobuf to a collector.
- **Why here**: ADR-0010 (OTLP push over Prometheus scrape). The UI's
  OTel Web SDK (ADR-0009 Phase B) emits browser spans that correlate
  end-to-end with backend Java spans.

#### W3C Trace Context
- **Reference**: [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- **What it does**: Standardised `traceparent` header propagation.
- **Why here**: Makes browser ↔ backend trace correlation automatic
  in Tempo Explore. Click a span, jump to its parent in the UI.

### 4.3 Logging

#### Structured JSON logging
- **Reference**: [logstash-logback-encoder](https://github.com/logfellow/logstash-logback-encoder)
- **What it does**: Every log line is one JSON object with `traceId`,
  `spanId`, MDC keys — grepable by field, not by regex.
- **Why here**: Loki's LogQL can filter `{app="mirador"} | json | traceId=...`
  without parsing. Key prefix conventions (e.g. `kafka_enrich_timeout`)
  enable dashboard queries.

#### Log correlation via MDC
- **Reference**: [SLF4J MDC](https://logback.qos.ch/manual/mdc.html)
- **What it does**: Thread-local context (request ID, customer ID,
  tenant ID) that automatically appears in every log line emitted
  during that logical operation.
- **Why here**: Makes "what happened to request X" answerable with a
  single Loki query.

### 4.4 Profiling

#### Continuous profiling
- **Reference**: [Pyroscope docs](https://pyroscope.io)
- **What it does**: CPU / memory / goroutine profiles collected
  continuously in production, visualisable as flame graphs.
- **Why here**: Bundled into the LGTM stack; useful for a live demo
  of "where is the JVM spending time right now?". Zero ops cost.

---

## 5. Resilience

### 5.1 Failure management

#### Circuit breaker
- **Reference**: [Martin Fowler — CircuitBreaker](https://martinfowler.com/bliki/CircuitBreaker.html)
- **What it does**: Trips after N failures, short-circuits to a
  fallback while the downstream heals, then tries again.
- **Why here**: Resilience4j `@CircuitBreaker` on `BioService` (Ollama),
  demonstrable via the Chaos page triggers. The fallback string
  ("Bio temporarily unavailable") is the demo narrative.

#### Bulkhead
- **Reference**: [Michael Nygard — Release It!](https://pragprog.com/titles/mnee2/release-it-second-edition/)
  (ch. Bulkheads)
- **What it does**: Caps concurrent calls to a slow dependency so a
  traffic spike doesn't exhaust the caller's thread pool.
- **Why here**: `@Bulkhead(name = "ollama")` caps Ollama calls at 5
  concurrent — prevents a slow LLM from DoS-ing the JVM.

#### Rate limiting
- **Reference**: [Bucket4j — token-bucket](https://bucket4j.com/docs/)
- **What it does**: Token bucket algorithm — N requests per window,
  excess gets HTTP 429.
- **Why here**: 100 req/min per IP demonstrably, tune-able, exercisable
  from the Chaos page.

#### Idempotency
- **Reference**: [Stripe — Idempotency in APIs](https://stripe.com/docs/api/idempotent_requests)
- **What it does**: Client-supplied `Idempotency-Key` header + server-
  side cache ensures a retried POST doesn't create duplicates.
- **Why here**: `IdempotencyFilter` on `POST /customers`. Fundamental
  API robustness pattern; the Diagnostic page has a test scenario.

### 5.2 Coordination patterns

#### Request-reply over Kafka
- **Reference**: [Spring Kafka — ReplyingKafkaTemplate](https://docs.spring.io/spring-kafka/reference/kafka/sending-messages.html#replying-template)
- **What it does**: Synchronous-feeling RPC over two Kafka topics
  with correlation IDs.
- **Why here**: Shows that Kafka isn't fire-and-forget-only. The
  `/customers/{id}/enrich` endpoint blocks on a reply with a
  5-second timeout → 504.

#### Outbox pattern — partially applied
- **Reference**: [Chris Richardson — Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- **What it does**: Persist the event in the same DB transaction as
  the business write; a relay publishes it to Kafka after commit.
- **Why here**: The demo uses a simpler "publish after save" pattern
  because transactional writes across DB + Kafka would require a
  Debezium connector — out of scope for the portfolio demo, noted
  as a ROADMAP Tier-2 upgrade.

---

## 6. Security

### 6.1 Authentication & authorization

#### JWT with refresh-token rotation
- **Reference**: [RFC 6749 §6 (OAuth refresh)](https://datatracker.ietf.org/doc/html/rfc6749#section-6)
- **What it does**: Short-lived access token + longer-lived refresh
  token that's rotated on every use (old invalidated, new issued).
- **Why here**: ADR-0018. HS256 access tokens in-memory + opaque
  refresh tokens in Postgres with Redis blacklist on logout.
  Covers the hard questions: expiration, revocation, replay.

#### OIDC with JWKS
- **Reference**: [OpenID Connect Core](https://openid.net/specs/openid-connect-core-1_0.html)
- **What it does**: Identity provider publishes signing keys at a
  well-known URL; resource servers verify tokens without a shared
  secret.
- **Why here**: Keycloak path + Auth0 path. Demonstrates federated
  identity alongside the built-in JWT — two auth strategies in one
  filter chain.

### 6.2 Supply chain

#### SBOM generation + verification
- **Reference**: [CycloneDX spec](https://cyclonedx.org/)
- **What it does**: Every image ships a Software Bill of Materials —
  list of packages + versions + licenses — attached as an OCI artifact.
- **Why here**: Grype + Trivy scan the SBOM, not the image — cheaper,
  more accurate. Cosign signs the SBOM alongside the image.

#### Container image signing
- **Reference**: [sigstore.dev](https://www.sigstore.dev/)
- **What it does**: cosign signs OCI artifacts; the public transparency
  log (Rekor) makes signature forgery detectable.
- **Why here**: Paired with a Kyverno admission rule (ROADMAP Tier-2)
  this closes the supply-chain loop: only signed images admitted.

### 6.3 Runtime security

#### Pod Security Standards — restricted profile
- **Reference**: [k8s PSS docs](https://kubernetes.io/docs/concepts/security/pod-security-standards/)
- **What it does**: Admission policy that rejects pods violating the
  `restricted` profile (no privileged, no root, no hostNetwork, etc.).
- **Why here**: Namespace labels on `app` enforce `restricted`; `infra`
  is `baseline` (some stateful images aren't restricted-ready).
  Rejection happens at the API server, before the pod is created.

#### NetworkPolicy default-deny
- **Reference**: [k8s NetworkPolicy docs](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- **What it does**: No traffic allowed between pods by default; every
  communication must be explicitly opened.
- **Why here**: `deploy/kubernetes/base/networkpolicies.yaml` enforces
  deny-all + per-direction allow rules. Catches a compromised pod
  trying to exfiltrate via unexpected destinations.

#### Policy-as-code (Kyverno)
- **Reference**: [kyverno.io](https://kyverno.io)
- **What it does**: Declarative admission policies — "require resource
  limits", "disallow privileged", "require probes".
- **Why here**: Kyverno is installed; the catalogue of policies is on
  ROADMAP Tier-2. Shows the gap between "we have an admission engine"
  and "we enforce the rules we care about".

---

## 7. Cost & FinOps

#### Budget alerting + auto-destroy
- **Reference**: `docs/ops/cost-control.md`
- **What it does**: GCP budget fires at 50/80/100 % thresholds; the
  100 % threshold triggers a Cloud Function that deletes the cluster.
- **Why here**: Cost discipline automation. A forgotten cluster running
  all month would blow the €2/month target 100× — the kill-switch is
  the enforcement mechanism.

#### Explicit cost model per component
- **Reference**: [`cost-model.md`](cost-model.md)
- **What it does**: Table of every component with its €/month rate.
- **Why here**: Before adding a component, the rate is known — the
  decision is conscious. Prevents the "it's cheap" optimism that
  accumulates into a €500 bill.

---

## 8. AI-assisted engineering

#### LLM-authored ADRs
- **Reference**: README "AI-assisted integration" section
- **What it does**: LLM drafts the context/decision/alternatives/
  consequences structure from a bullet briefing; human edits for
  judgement and rejection reasoning.
- **Why here**: Produces 30+ consistent ADRs in the time it takes to
  write three by hand. The human arbitrage survives the assistant;
  the style uniformity is free.

#### LLM-assisted CI hardening
- **Reference**: commit log of `.gitlab-ci.yml` 2025-2026
- **What it does**: Tighten rules (`allow_failure`, `rules: if:`),
  enforce supply-chain steps, add `interruptible: true`.
- **Why here**: Pattern suggestions from the LLM based on reading
  countless CI post-mortems — the human role is picking which ones
  fit the project's shape.

#### Human-only decisions — explicit
- **Reference**: README "Where AI was wrong and I had to overrule it"
- **What it does**: Public list of cases where the LLM's first output
  was wrong (cost estimate off 2 orders, DNS NetworkPolicy misaligned
  to Autopilot, Spring AI shim removal premature).
- **Why here**: Calibration. Shows that the collaboration is real, not
  uncritical acceptance.

---

## Cross-references

- Tool catalogue: [`technologies.md`](technologies.md)
- Cost reference: [`cost-model.md`](cost-model.md)
- Architectural decisions: [`../adr/`](../adr/)
- Operational runbooks: [`../ops/runbooks/`](../ops/runbooks/)

## Maintenance rule

When a new pattern lands in the repo, a new entry goes here **in
the same MR**. A pattern that's used but undocumented here is a
pattern the next maintainer will reinvent.
