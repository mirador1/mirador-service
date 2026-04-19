# CI stages + jobs catalogue (GitLab `.gitlab-ci.yml`)

The pipeline lives in [`.gitlab-ci.yml`](../../.gitlab-ci.yml). This
file is the **flat index** — one row per job, sorted by stage —
so a visitor can answer "what does the CI actually do?" without
scrolling 1900 lines.

For the **why** behind multiple CI surfaces (GitLab vs GitHub vs
Jenkinsfile), see [`ci-philosophy.md`](ci-philosophy.md). For
**measured durations**, see [`ci-timings.md`](ci-timings.md).

---

## Pipeline workflow rules

The pipeline runs only on:

- **Merge requests** (`merge_request_event`)
- **Default branch pushes** (`main`)
- **Scheduled pipelines** (nightly cron)

Pure documentation pushes (touching only `**/*.md` outside the trigger
allowlist) do not start a pipeline — see the `workflow.rules` block at
the top of `.gitlab-ci.yml`.

---

## Stages (top-level groups, executed in order)

| # | Stage | Purpose |
|---|---|---|
| 1 | `pages` | GitLab Pages static site (ADR-0022 landing page) |
| 2 | `lint` | Static file checks — instant feedback, no compilation |
| 3 | `test` | Unit tests + SAST + secret scan — fast, no Docker |
| 4 | `integration` | Testcontainers ITs + SpotBugs + coverage — needs Docker |
| 5 | `k8s` | kind-in-docker manifest apply + probe (ADR-0028) |
| 6 | `package` | Produce deployable artefacts — JAR + Docker image |
| 7 | `compat` | On-demand compatibility matrix: SB3/SB4 × Java 17/21/25 |
| 8 | `native` | GraalVM AOT native-image build (schedule only) |
| 9 | `sonar` | SonarQube static analysis — reads `integration` coverage |
| 10 | `reports` | Maven site + docs generation (`REPORT_PIPELINE=true`) |
| 11 | `infra` | Terraform plan/apply for GCP infrastructure (manual) |
| 12 | `deploy` | Deploy to K8s / PaaS — 6 targets, all manual |

---

## Jobs by stage

### `pages` — GitLab Pages

| Job | What it does | Trigger |
|---|---|---|
| `pages` | Publishes the `public/` static site to `https://mirador1.gitlab.io/mirador-service/` (ADR-0022 landing page) | main branch |

### `lint` — Static checks (no compilation)

| Job | What it does | Trigger |
|---|---|---|
| `hadolint` | Lints every `Dockerfile*` against best-practice rules | MR + main |
| `openapi-lint` | Boots Spring → fetches `/v3/api-docs` → runs Spectral OAS3 checks (operationId unique, tags defined, no schema siblings, etc.). Pinned to `@stoplight/spectral-cli@6.15.1`. | MR (when API code or `.spectral.yaml` changes) + main |

### `test` — Unit + SAST + secrets

| Job | What it does | Trigger |
|---|---|---|
| `unit-test` | `mvn test` — 137 JUnit 5 tests, no Docker. Produces `target/jacoco.exec` for coverage merge. | MR + main |
| `sast` | GitLab built-in Static Application Security Testing template | MR + main |
| `dependency_scanning` | GitLab built-in dependency scanning template | MR + main |
| `owasp-dependency-check` | OWASP DC scan against the NVD feed; CVSS gate. Cached NVD DB reduces runtime by ~60 %. | MR + main |
| `secret-scan` | gitleaks scan of the diff. Blocks on any high-confidence finding. | MR + main |
| `renovate-lint` | Validates `renovate.json` syntax via `renovate-config-validator`. | MR (when `renovate.json` changes) |
| `release-please` | release-please drafts a release MR on every push to main. | main |

### `integration` — Testcontainers ITs

| Job | What it does | Trigger |
|---|---|---|
| `integration-test` | `mvn verify -Dsurefire.skip=true`, runs the `@Tag("keycloak-heavy")` group as **excluded** by default. Spawns Postgres + Kafka + Redis Testcontainers; produces `target/site/jacoco-it/jacoco.xml` for Sonar merge. Retries once on `runner_system_failure`. | MR + main |
| `integration-test:keycloak` | Same `mvn verify` but with `-Dgroups=keycloak-heavy` — runs **only** Keycloak-heavy ITs (KeycloakAuthITest). Gated to weekly schedule (`KEYCLOAK_NIGHTLY=1`) or manual web trigger. Memory-heavy by design — see ADR-0034. | schedule + manual |

### `k8s` — kind cluster (ADR-0028)

| Job | What it does | Trigger |
|---|---|---|
| `test:k8s-apply` | Spins up a `kind` cluster inside the runner, applies the Kustomize overlay, waits for readiness probes. Catches manifest regressions before they reach GKE. | MR (when `deploy/k8s/**` changes) + main |

### `package` — Build artefacts

| Job | What it does | Trigger |
|---|---|---|
| `build-jar` | `mvn package -DskipTests` produces the runnable Spring Boot fat JAR. | main + tag |
| `docker-build` | `docker buildx build --platform linux/amd64 --push` cross-compiles on the arm64 macbook-local via QEMU; pushes to `$CI_REGISTRY_IMAGE`. Cache pulled/pushed via `type=registry`. | main + tag |
| `trivy:scan` | CVE scan of the freshly built image (`aquasec/trivy:0.69.3`, pinned). `allow_failure: true` informational. | main + tag |
| `hadolint` (in package stage) | Re-runs hadolint against the actual image's labels. | main + tag |
| `sbom:syft` | Generates `bom.cdx.json` (CycloneDX) + `bom.spdx.json` from the pushed image. Retry on `runner_system_failure` for known Go/QEMU `lfstack.push` flake. | main + tag |
| `grype:scan` | Reads the SBOM, matches against the Grype vulnerability DB. Informational. | main + tag |
| `dockle` | CIS Docker policy checks. `--image-platform linux/amd64` set explicitly because the image is single-arch amd64. `allow_failure: true`. | main + tag |
| `cosign:sign` | Sigstore keyless signing of the image via GitLab OIDC + Fulcio. Image runs in plain `alpine:3.22` + `curl` install of the cosign binary (the upstream gcr.io image has no shell). `allow_failure: true` until the deploy verifies signatures. | main + tag |
| `cosign:verify` | Supply-chain gate: verifies the just-signed image's Fulcio cert chain belongs to THIS GitLab project (`--certificate-identity-regexp` on the GitLab OIDC issuer). Every `deploy:*` job has `needs: [cosign:verify]` so a tampered/unsigned image cannot reach prod. | main + tag |

### `compat` — Compatibility matrix

| Job | What it does | Trigger |
|---|---|---|
| `compat-sb3-java17` | `mvn verify -Dsb3 -Djava17` — Spring Boot 3 + Java 17. | nightly schedule |
| `compat-sb3-java21` | `mvn verify -Dsb3` — Spring Boot 3 + Java 21. | nightly schedule |
| `compat-sb4-java17` | `mvn verify -Dcompat -Djava17` — Spring Boot 4 + Java 17. | nightly schedule |
| `compat-sb4-java21` | `mvn verify -Dcompat` — Spring Boot 4 + Java 21. | nightly schedule |

### `native` — GraalVM AOT

| Job | What it does | Trigger |
|---|---|---|
| `build-native` | `mvn -Pnative native:compile` — produces a static AOT binary. Documented Tier-2 in the ROADMAP; runs only on the `native-build` schedule to avoid burning runner minutes on every push. | `native-build` schedule |

### `sonar` — SonarCloud

| Job | What it does | Trigger |
|---|---|---|
| `sonar-analysis` | `mvn sonar:sonar` — uploads `target/site/jacoco/jacoco.xml` + `target/site/jacoco-it/jacoco.xml` so the dashboard shows merged unit + IT coverage. Quality gate enforced via `sonar.qualitygate.wait=true`. | MR + main |
| `code-quality` | Outputs GitLab Code Quality JSON from PMD + Checkstyle + SpotBugs, surfaced as inline comments on MRs. | MR + main |
| `semgrep` | OSS rule scan in addition to GitLab's SAST. | MR + main |

### `reports` — Site generation (opt-in)

| Job | What it does | Trigger |
|---|---|---|
| `generate-reports` | `mvn site` produces the full Maven Site (Javadoc, project info, JaCoCo, SpotBugs HTML, …) and uploads as 30-day artefact. Skipped by default — opt in via `REPORT_PIPELINE=true` in the CI variables. | manual / scheduled with var |
| `mutation-test` | `mvn pitest:mutationCoverage` — PIT mutates bytecode (swap `>` for `>=`, return null, …) and re-runs the test suite to measure TEST QUALITY (not just line coverage). 70 % mutation-score gate on `com.mirador.*`. ~15 min. Excludes `keycloak-heavy` group (ADR-0034). | manual / scheduled (REPORT_PIPELINE=true) |

### `infra` — Terraform

| Job | What it does | Trigger |
|---|---|---|
| `terraform-plan` | `terraform plan` against the GCP project. Shows diff in MR comments. | MR (when `infra/terraform/**` changes) |
| `terraform-apply` | `terraform apply` — manual button on main only. | main, manual |

### `deploy` — 6 targets, all manual

| Job | Target | When |
|---|---|---|
| `smoke-test` | curl /actuator/health on every deploy target after deploy | manual |
| `deploy:eks` | AWS EKS via `kubectl apply` | manual on main |
| `deploy:aks` | Azure AKS via `kubectl apply` | manual on main |
| `deploy:cloud-run` | GCP Cloud Run | manual on main |
| `deploy:fly` | Fly.io | manual on main |
| `deploy:k3s` | k3s / self-hosted | manual on main |

---

## CI variables (env)

See [`ci-variables.md`](ci-variables.md) for the full list. The
critical ones for understanding the pipeline are:

- `MAVEN_OPTS=-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository -Xmx1800m`
- `MAVEN_CLI_OPTS=--batch-mode --errors --fail-at-end --show-version --no-transfer-progress`
- `TESTCONTAINERS_RYUK_DISABLED=true`

Per-job overrides (e.g. `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`
on `integration-test`) are documented inline in the YAML.

---

## Maintenance rule

When you add a new job to `.gitlab-ci.yml`:

1. Place it under its appropriate stage in the YAML.
2. Add one row to the table above (under that stage's section).
3. If the job creates a recurring artefact, list it here too.

When you remove a job, delete the row. **Do not let this file drift
out of sync** — a reader who finds a job in the YAML that's not here
should treat it as a documentation bug and open a fix MR.
