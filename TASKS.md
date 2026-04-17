# Mirador Service — Persistent Task Backlog

<!--
  Source of truth for pending work across Claude sessions.
  Read at session start; update immediately on task changes.
  Format: - [ ] pending   - [~] in progress   - [x] done (keep last 10 done for context)
  When all tasks are done, delete this file and commit the deletion.
-->

## Pending — Cluster bring-up

- [~] **deploy:gke first run** — pipeline on `main` builds image + deploys
      to GKE Autopilot. Once Cloud SQL is provisioned (below), cert-manager
      emits a Let's Encrypt cert and HTTPS goes live at
      `https://mirador1.duckdns.org`.

- [ ] **Cloud SQL instance** — provision via `gcloud` (faster iteration
      than fixing the current terraform-plan "bucket doesn't exist" error).
      Commands are documented in
      `deploy/kubernetes/overlays/gke/cloud-sql-proxy.yaml` (setup section).
      Then:
      1. `gcloud sql instances describe mirador-db --format='value(connectionName)'`
      2. Set GitLab CI var `CLOUD_SQL_INSTANCE=$GCP_PROJECT:$GKE_REGION:mirador-db`
      3. Re-trigger deploy:gke — the GKE Kustomize overlay embeds the
         sidecar patch + ConfigMap override automatically.
      4. After verification, **pause the instance** to minimise cost.

- [ ] **Terraform apply** — after Cloud SQL is manual-provisioned and the
      terraform-plan bucket issue is resolved, run the full terraform-apply
      to bring VPC + GKE Autopilot + Memorystore Redis + IAM SAs into code.

- [ ] **Managed Kafka on GCP** (~$35/day) — deferred. Requires
      uncommenting ~70 lines in `deploy/terraform/gcp/kafka.tf` + SASL
      config in backend ConfigMap. See ADR-0005 for the cost trade-off.

## Pending — Version upgrades (deferred majors, separate MRs each)

- [ ] **Spring AI** `1.0.0-M6` → `1.1.4` GA (or `2.0.0-M4`). Closes 5
      known CVEs. Requires artifact rename
      (`spring-ai-ollama-spring-boot-starter` → `spring-ai-starter-model-ollama`)
      and API-surface validation.
- [ ] **ShedLock** 6 → 7 (major; distributed-lock library).
- [ ] **Testcontainers** 1.21 → 2.0 (major; breaking changes).
- [ ] **testcontainers-keycloak** 3 → 4 (major).
- [ ] **Checkstyle** 10.26.1 → 13.4.0 (3 majors; Checkstyle-config review).

## Pending — Industry-standard upgrades

- [~] **Argo CD GitOps** — scaffolding at
      [`deploy/argocd/application.yaml`](deploy/argocd/application.yaml)
      ready to apply once Argo CD is installed on the cluster. Cutover
      procedure documented in [`deploy/argocd/README.md`](deploy/argocd/README.md).
      Remaining: install Argo CD in GKE, apply the Application, remove
      the `deploy:gke` CI job.

- [~] **External Secrets Operator + Google Secret Manager** — scaffolding
      at [`deploy/external-secrets/`](deploy/external-secrets/)
      (SecretStore + ExternalSecret using Workload Identity, kept
      outside `deploy/kubernetes/base/` until the CRDs are installed
      so the CI k8s-dry-run hook doesn't fail on unknown kinds).
      Cutover procedure documented in the same folder's README.
      Remaining: `helm install external-secrets`, create GCP secrets,
      grant IAM, move the files under `base/external-secrets/`, wire
      them into `base/kustomization.yaml`, and drop the
      `kubectl create secret generic mirador-secrets` step in CI.

- [ ] **distroless java25 image** — switch once Google publishes it (track
      https://github.com/GoogleContainerTools/distroless). Drops ~90 CVEs
      vs `eclipse-temurin:25-jre`.

- [ ] **Argo Rollouts / Flagger** — progressive traffic split for canary
      deploys. Requires Istio or Linkerd; deferred until Argo CD lands.

## Recently Completed

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
