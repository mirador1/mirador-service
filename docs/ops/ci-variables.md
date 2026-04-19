# GitLab CI variables — reference

Every variable referenced in `.gitlab-ci.yml`, grouped by purpose.
Says what it's for, whether it's **required**, **optional**,
**auto-set by GitLab**, or **runtime-supplied** (injected at
application boot, not at CI time).

Maintenance rule: when a new `$VAR` lands in `.gitlab-ci.yml`, add a
row here **in the same commit**. A variable that's used but
undocumented fails the next team member's onboarding.

Configure variables at:
`https://gitlab.com/<project>/-/settings/ci_cd` → Variables.

## Required for the canonical pipeline to green

| Variable | Scope | Type | Masked | Protected | What it's for |
|---|---|---|---|---|---|
| `SONAR_TOKEN` | main + MR | Secret text | ✅ | ✅ | SonarCloud auth. Obtained at https://sonarcloud.io/account/security. Without it, the `sonar-analysis` job self-disables (rule `if: $SONAR_TOKEN == null`) — pipeline stays green but code quality is not pushed. |
| `GITHUB_MIRROR_SSH_KEY` | main | Secret text | ✅ | ✅ | Base64-encoded ed25519 private key for the `github-mirror` job. Base64 because masked variables must be single-line printable. Public counterpart is a Deploy Key (write) on the GitHub repo. Full setup: [`docs/ops/github-mirror.md`](github-mirror.md). |

## Optional — release automation

| Variable | Scope | Type | Masked | Protected | What it's for |
|---|---|---|---|---|---|
| `RELEASE_PLEASE_TOKEN` | main | Secret text | ✅ | ✅ | Fine-grained GitLab PAT scoped to the same project with `api` + `write_repository`. release-please needs it to open MRs + create tags. Without it, the `release-please` job has `allow_failure: true` and no release MR gets opened. |

## Optional — multi-cloud deploy targets

Each deploy target job (`deploy:eks`, `deploy:aks`, `deploy:fly`, …)
is `when: manual` + `allow_failure: true`. It won't run unless a
human clicks "Play" AND the target-specific variables below are set.

| Variable | Target | Type | What it's for |
|---|---|---|---|
| `AWS_REGION` | EKS | Variable | Target AWS region (e.g. `eu-west-3`). |
| `EKS_CLUSTER` | EKS | Variable | Cluster name for `aws eks update-kubeconfig`. |
| `AZURE_TENANT` | AKS | Variable | Azure tenant ID for service-principal login. |
| `AZURE_APP_ID` | AKS | Variable | Azure service-principal application ID. |
| `AZURE_PASSWORD` | AKS | Secret text (masked) | SP password. |
| `AKS_CLUSTER` | AKS | Variable | AKS cluster name. |
| `AKS_RG` | AKS | Variable | AKS resource group. |
| `FLY_API_TOKEN` | Fly.io | Secret text (masked) | `flyctl` API token. |
| `GCP_WIF_PROVIDER` | GKE | Variable | Workload Identity Federation provider path (e.g. `projects/<num>/locations/global/workloadIdentityPools/mirador/providers/gitlab`). |
| `GCP_SERVICE_ACCOUNT` | GKE | Variable | Target GCP service account email (e.g. `mirador-ci@<proj>.iam.gserviceaccount.com`). |
| `GCP_PROJECT` | GKE | Variable | GCP project ID. |
| `GCP_OIDC_TOKEN` | GKE | Auto (JWT) | ID token GitLab mints for WIF — do NOT set manually; comes from the `id_tokens:` block in the job. |
| `CLOUD_SQL_INSTANCE` | GKE | Variable | Cloud SQL instance connection name (if re-enabled — currently ADR-0013 keeps in-cluster PG). |
| `K8S_HOST` / `K8S_SERVER` / `K8S_CA_CERT` / `K8S_TOKEN` | self-hosted k3s | Mixed | Generic Kubernetes target, for clusters outside AWS/Azure/GCP. `K8S_CA_CERT` is base64-encoded. |
| `IMAGE_REGISTRY` | package | Variable | Registry hostname (e.g. `europe-west1-docker.pkg.dev/<proj>/mirador`). Defaults to `$CI_REGISTRY`. |

## Schedule triggers (manual — "Run pipeline" with variable override)

| Variable | Value | What it unlocks |
|---|---|---|
| `REPORT_PIPELINE` | `true` | Enables the `reports` stage (Maven site, TypeDoc, Compodoc, Semgrep SARIF). Heavy (~8 min); runs only on explicit trigger or nightly schedule. |
| `RUN_COMPAT` | `true` | Enables the compat matrix (`compat-sb3-java17`, `compat-sb4-java21`, …). Five parallel jobs; only runs on request. |

## Runtime-supplied (NOT CI variables)

These are injected into the running application by External Secrets
Operator + GCP Secret Manager at runtime, never through GitLab CI.
Listed here because they appear in error logs / `.env` examples and
could be confused with CI variables.

| Variable | Where it comes from | What it's for |
|---|---|---|
| `DB_PASSWORD` | GSM `mirador-db-password` → ESO → K8s Secret `mirador-secrets` | Postgres user password |
| `JWT_SECRET` | GSM `mirador-jwt-secret` → ESO → K8s Secret `mirador-secrets` | HS256 signing key (256 bits) |
| `API_KEY` | GSM `mirador-api-key` → ESO | External integration token |
| `GITLAB_API_TOKEN` | GSM `mirador-gitlab-api-token` → ESO | For the pipeline-monitor feature in the UI |
| `KEYCLOAK_ADMIN_PASSWORD` | GSM → `keycloak-secrets` | Keycloak bootstrap |
| `KC_DB_PASSWORD` | GSM → `keycloak-secrets` | Keycloak's own Postgres user |

On kind (local K8s, ADR-0028), these same K8s Secrets are
hard-coded in [`deploy/kubernetes/overlays/local/local-secrets.yaml`](../../deploy/kubernetes/overlays/local/local-secrets.yaml)
with demo values. That's safe because kind clusters never see
production data.

## Auto-set by GitLab (no configuration needed)

For completeness — these appear in the pipeline but are provided
by GitLab automatically:

- `CI_COMMIT_SHA`, `CI_COMMIT_BRANCH`, `CI_DEFAULT_BRANCH`,
  `CI_PIPELINE_SOURCE`, `CI_PROJECT_DIR`, `CI_JOB_ID`,
  `CI_REGISTRY`, `CI_REGISTRY_USER`, `CI_REGISTRY_PASSWORD`,
  `CI_REPOSITORY_URL` — see
  [GitLab predefined variables](https://docs.gitlab.com/ee/ci/variables/predefined_variables.html).
- `SIGSTORE_ID_TOKEN` — OIDC token for keyless cosign signing
  (`id_tokens:` block in the `cosign:sign` job definition).

## Bootstrap checklist for a fresh project clone

Minimum to get the canonical pipeline green (no optional features):

```
# At https://gitlab.com/<project>/-/settings/ci_cd → Variables
1. SONAR_TOKEN          — create at sonarcloud.io/account/security, mask + protect
2. GITHUB_MIRROR_SSH_KEY — only if you want the GitHub mirror; base64 ed25519 priv key,
                          mask + protect. See docs/ops/github-mirror.md.
```

That's it. Everything else either self-disables (rule-gated), is
optional (manual deploy), or is auto-provided by GitLab.

## Verify the variables actually work

```bash
# From your shell with glab authenticated
glab variable list
# Shows keys + masked/protected flags. Values never printed.

# Trigger a pipeline to smoke-test
glab ci run --branch dev
glab ci status
```

If a required variable is missing, the job that needs it either
skips (with a rule) or fails at the first command that references
the empty variable. The failure message is deterministic per job,
so greping the job log usually points at the missing variable by
name.
