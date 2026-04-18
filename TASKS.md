# Mirador Service — Persistent Task Backlog

<!--
  Source of truth for pending work across Claude sessions.
  Read at session start; update immediately on task changes.
  Format: - [ ] pending   - [~] in progress   - [x] done (keep last 10 done for context)
  When all tasks are done, delete this file and commit the deletion.
-->

## Pending — Cluster bring-up

- [ ] **Terraform .tf cleanup** — running `terraform plan` (2026-04-18)
      reveals 14 resources to create, but several already exist
      out-of-Terraform (GKE cluster `mirador-prod`, VPC) and others are
      no longer desired per ADR-0013 (Cloud SQL) / ADR-0014 (single
      replica, no HA). A session dedicated to rewriting the .tf files
      to match current reality is needed:
      - Drop `google_sql_database_instance`, `google_sql_*`,
        `google_service_networking_connection` (Cloud SQL stack).
      - Drop `google_redis_instance` if in-cluster Redis stays (ADR
        pending — currently redis is in `infra` StatefulSet).
      - Import existing `google_container_cluster.autopilot`,
        `google_compute_network.vpc`, `google_compute_subnetwork.subnet`,
        `google_compute_router.*` into state via `terraform import`.
      - Keep VPC + NAT + Workload Identity SA + IAM bindings.
      After cleanup, `terraform apply` is safe and becomes a no-op.

- [ ] **Managed Kafka on GCP** (~$35/day) — deferred. Requires
      uncommenting ~70 lines in `deploy/terraform/gcp/kafka.tf` + SASL
      config in backend ConfigMap. See ADR-0005 for the cost trade-off.

## Pending — Version upgrades (deferred majors, separate MRs each)

- [ ] **Testcontainers** 1.21 → 2.0 — **blocked**. 2.0.x core is on
      Maven Central but companion modules (`junit-jupiter`,
      `postgresql`, `kafka`) only ship for 1.21.x; stay on 1.21.4
      until modules catch up. Revisit periodically.

## Pending — Industry-standard upgrades

- [~] **External Secrets Operator → Google Secret Manager cutover** —
      operator is installed (2026-04-18), CRDs available. Remaining
      user steps documented in ADR-0016: create the GSM entries,
      grant the WIF-backed SA `roles/secretmanager.secretAccessor`,
      move `deploy/external-secrets/*` into `base/external-secrets/`,
      delete the hand-created `mirador-secrets` + `keycloak-secrets`
      from the cluster.

- [ ] **distroless java25 image** — switch once Google publishes it (track
      https://github.com/GoogleContainerTools/distroless). Drops ~90 CVEs
      vs `eclipse-temurin:25-jre`.

- [ ] **Argo Rollouts / Flagger** — progressive traffic split for canary
      deploys. Requires Istio or Linkerd; deferred (ADR-0015 notes it
      as a future upgrade path).

- [ ] **Retire `deploy:gke` CI job** — now that Argo CD reconciles the
      GKE overlay from main, the job is redundant. Keeping it until
      the ConfigMap placeholder migration is confirmed stable.

## Recently Completed

- [x] **GKE cluster bring-up — Argo CD GitOps + in-cluster everything**
      (2026-04-18). Argo CD installed (core subset, 4 pods), External
      Secrets Operator installed (3 pods), both fit on the existing
      2-node Autopilot without a quota bump thanks to
      ADR-0014's resource-tight policy. cert-manager + otel-operator
      resource requests shrunk to match. 7 legacy `customer-service/ui`
      deployments cleaned. Argo CD `Application/mirador` deployed,
      ingress UI at `argocd.mirador1.duckdns.org` (TLS via Let's
      Encrypt, same chain as the app). ADR-0013/0014/0015/0016/0017/
      0018/0019/0020 added to record the day's decisions.
- [x] **Spring AI 1.0.0-M6 → 1.1.4 GA**. Artifact rename
      (`spring-ai-ollama-spring-boot-starter` →
      `spring-ai-starter-model-ollama`); the two SB4-compat shims
      under `src/main/java/org/springframework/boot/autoconfigure/`
      are deleted (Spring AI 1.1 is built against Spring Boot 4
      natively, so the @ImportAutoConfiguration shim targets are
      no longer referenced). Closes 5 CVEs carried by M6. ChatClient
      API unchanged — BioService + OllamaHealthIndicator untouched.
- [x] **Version bumps (post-Sonar)**: Checkstyle 10.26.1 → 13.4.0
      (3 majors; Java 25 parser verified clean, only stylistic
      warnings emitted), ShedLock 6.0.2 → 7.7.0 (same import paths),
      testcontainers-keycloak 3.5.0 → 4.1.1 (API compatible),
      tools.jackson.core 3.1.0 → 3.1.1 pinned (CVE
      GHSA-2m67-wjpj-xhg9).
- [x] **SonarCloud cleanup — zero actionable issues**. Five merged MRs
      (56-60) drove the inventory from 146 open issues to 0: S7467
      (unused-catch pattern), S1128 (unused imports), S1192 (literal
      constants), S1710 (flattened @ApiResponses wrappers), S6068
      (redundant eq() matchers), S1874 (JSpecify migration), S1130
      (unused throws), S1141 (nested try — parseOneSuite / ParsedSuite
      record, parseDurationSeconds), S135 (multi-break parsers), S5838
      (AssertJ idioms), S1481/S1168/S2094 (suppressions with intent),
      S6353/S2293/S6813/S8491, S125/S1135/S107/S3776/S6541/S5853/S5976,
      javasecurity:S3649 + S5131 (intentional-demo suppressions +
      HtmlUtils.htmlEscape on /xss-safe), Trivy GHSA-2m67-wjpj-xhg9
      (jackson-core 3.1.0 → 3.1.1 pinned).
- [x] ADR-0009 (container runtime: `eclipse-temurin:25-jre`), ADR-0010
      (OTLP push to Collector, not Prometheus scrape), and ADR-0011
      (minimal `@Transactional` surface, no `readOnly = true`) — three
      previously tacit decisions now recorded.
- [x] Tech glossary verification pass — corrected SSE (actively used via
      `SseEmitterRegistry`), consolidated the duplicate SSE entry, removed
      WireMock + JSONB placeholder entries (neither present in code), and
      tightened Memorystore / Artifact Registry usage claims.
- [x] OpenAPI lint via Spectral (`.spectral.yaml` + CI job).
- [x] k6 post-deploy smoke test (`scripts/load-test/smoke.js` + CI job).
- [x] `.mise.toml` pins Java 25 + Maven + kubectl + Terraform + gcloud +
      Node per repo (commit `8bbd8f9`).
- [x] Kustomize base + overlays (`local`/`gke`/`eks`/`aks`) with postgres
      mini-base, TLS patch, Cloud SQL sidecar patch.
- [x] 8 ADRs under `docs/adr/` (Kustomize, Cloud SQL, local runner,
      in-cluster Kafka, version hoisting, WIF, feature-sliced packages).
- [x] Renovate + gitleaks + commitlint + release-please tooling.
- [x] K8s hardening — NetworkPolicies default-deny, PodDisruptionBudget,
      topologySpreadConstraints, PodSecurity admission, restricted
      securityContext + readOnlyRootFilesystem.
- [x] Docker image supply-chain — syft SBOM, Grype CVE scan, Dockle,
      hadolint, cosign keyless via GitLab OIDC + Sigstore.
- [x] 1100-line technology glossary (`docs/reference/technologies.md`).
- [x] Bumped 12 safe minor/patch versions (resilience4j, pyroscope, jjwt,
      maven plugins, spotbugs, pmd, asm, OTel appender, sonar-maven-plugin).
