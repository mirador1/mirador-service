# Mirador Service — Persistent Task Backlog

<!--
  Source of truth for pending work across Claude sessions.
  Read at session start; update immediately on task changes.
  Format: - [ ] pending   - [~] in progress   - [x] done (keep last 10 done for context)
  When all tasks are done, delete this file and commit the deletion.
-->

## Pending — Post-ADR-0025 follow-up

<!-- EnvService selector (Local / Kind / Prod) landed in mirador-ui MR 28. -->

- [ ] **unleash-proxy token bootstrap** — under `AUTH_TYPE=demo` Unleash 7.x
      rejects the static placeholder token with HTTP 401 on
      `/api/client/features`. Solution: a one-off `Job` or `initContainer`
      that calls Unleash admin API after boot, creates a real client API
      token, writes it to a `Secret`, which `unleash-proxy` consumes as
      `UNLEASH_API_TOKEN`. Without this, the browser's feature-flag SDK
      cannot fetch flags in the cluster (compose pgweb-local is unaffected).
      Everything else on the ADR-0026 wiring is green (validated 2026-04-18).

<!-- The mirador-ui docker image stays built + pushed. Even with ADR-0025
     (no prod deployment) the image is useful for: local prod-like run
     without `npm install`, CI integration tests against a built bundle,
     one-command demo. Only the K8s Deployment is dropped, not the CI build. -->

- [ ] **Move UI image pipeline to a `test-image` stage** (UI repo).
      Since the image is no longer a deploy artefact, mark it clearly
      as a validation artefact. Tag `:main-<sha>` only, no `:latest`.

- [ ] **UI repo — desktop deep-link buttons**. Wire the URI templates
      from `docs/getting-started/dev-tooling.md` into the Angular UI
      (Architecture + Database + Quality pages already have slots):
      `vscode://file/<abs>:<line>`, `idea://open?file=<abs>&line=<n>`,
      `docker-desktop://dashboard/container/<id>`, GitLab https URLs.
      Fails silently if the target app is not installed — no feature
      detection needed.

## Pending — Industry-standard upgrades

<!-- ESO cutover complete: ESO in base/external-secrets/, ESO + 5 GSM
     secrets live, the legacy deploy/external-secrets/ directory is gone.
     Validated on cluster 2026-04-18: kubectl get externalsecrets -A shows
     mirador-secrets + keycloak-secrets with STATUS: SecretSynced, READY: True. -->

- [ ] **Argo Rollouts / Flagger** — progressive traffic split for canary
      deploys. Requires Istio or Linkerd; deferred (ADR-0015 notes it
      as a future upgrade path).

<!-- distroless-java25 blocker is documented in the Dockerfile header
     (external, undated). Remove from tasks to avoid a rotting entry. -->


<!-- deploy:gke retired. Argo CD + :main image tag close the loop. -->


## Recently Completed

- [x] **Full nice-to-have stack landed** (2026-04-18 late session).
      LGTM stack in-cluster + Pyroscope + Argo Rollouts + Kyverno
      (helm via demo-up.sh) + Sentry SDK dep + cert-manager + Pages
      landing page. ADR-0023 locks in "stay on Autopilot". README
      rewritten with 4 manager-audience sections (arbitrages,
      simplify, AI positioning, compromises). 19 pods Running on the
      freshly-provisioned ephemeral cluster.
- [x] **Ephemeral cluster pattern** — ADR-0022. Cluster created from
      scratch by `terraform apply` (1 resource) on every demo; torn
      down by `bin/demo-down.sh`. Monthly bill ~€190 (24/7) → ~€2
      (2 h/mo demos).
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
