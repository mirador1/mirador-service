# ADR-0016: External Secrets Operator + Google Secret Manager

- **Status**: Accepted (operator installed, migration pending)
- **Date**: 2026-04-18

## Context

Secrets used by the backend (`mirador-secrets`, `keycloak-secrets`) are
currently created by a `kubectl create secret generic` step inside the
`deploy:gke` CI job. This works but has three problems:

1. **The secret values live in GitLab CI variables**. Rotating a
   password means editing CI → triggering a manual job → verifying.
   No audit trail on who read the secret or when.
2. **The secrets exist in two places** — GitLab CI and the cluster —
   and they can drift silently (a secret rotated on GCP but not
   re-injected via CI would look valid until the next deploy).
3. **No cross-cluster story**. If we replicate the demo on EKS/AKS/k3s
   tomorrow, every cluster needs the same secrets reproduced from the
   same source of truth — which today is nowhere central.

Google Secret Manager (GSM) is the GCP-native secret vault. Combined
with the External Secrets Operator (ESO), a cluster-resident Kubernetes
secret becomes a projection of a GSM entry, refreshed on a cron.

## Decision

**External Secrets Operator watches `SecretStore` + `ExternalSecret`
CRDs and materialises K8s Secrets from Google Secret Manager via
Workload Identity Federation** (no service-account JSON key on the
cluster — same pattern as ADR-0007).

Concretely:
- ESO is installed via the upstream Helm chart in the
  `external-secrets` namespace, with tight resource requests
  (ADR-0014).
- One `SecretStore` (namespace-scoped) per namespace that consumes
  secrets: `app`, `infra`. Both point at the same GSM project via WIF.
- One `ExternalSecret` CR per logical secret (`mirador-secrets`,
  `keycloak-secrets`), mapping GSM entries → K8s Secret keys. Refresh
  interval: 1 h.
- The scaffolding is ready under
  [`deploy/external-secrets/`](../../deploy/external-secrets/) but is
  kept out of `base/` until the GSM entries are actually created by
  the user (the k8s-dry-run CI hook would otherwise fail on
  unresolvable references).

## Alternatives considered

**HashiCorp Vault**:
- More feature-complete (dynamic secrets, PKI, ACLs).
- **Against**: too much platform component for a demo. Vault is a
  whole second cluster-resident service to run + back up + upgrade.
  The demo's secret set is tiny (5 keys) and static.

**Sealed Secrets** (bitnami):
- Encrypt secrets in git; only the cluster controller can decrypt.
- **Against**: the secrets are still in git (just encrypted), so
  key rotation requires a git rewrite. ESO's "git references a
  secret-manager entry by name" model is cleaner.

**GKE Secret Manager CSI driver**:
- Mounts GSM entries as files in pods, no K8s Secret intermediary.
- **Against**: most Spring Boot apps want `@Value` from env vars, not
  files. Would require a projected-volume-to-env shim. ESO keeps the
  standard env-from-secret pattern the code already uses.

## Consequences

Positive:
- Single source of truth for secrets: GSM. Rotation = `gcloud secrets
  versions add` (no CI change, no cluster change). ESO picks it up
  within the refresh interval.
- Same pattern on any cloud: swap the `SecretStore` backend
  (GCPSMBackend → AWSSecretsManagerBackend → AzureKeyVaultBackend)
  and the rest is identical.
- CI loses its `kubectl create secret` step — one less privilege for
  the CI token to carry.
- Audit trail: GSM tracks access via Cloud Audit Logs.

Negative:
- New CRDs (`SecretStore`, `ExternalSecret`) that Argo CD now manages.
- A short window where a GSM write hasn't propagated to the cluster
  yet. The 1 h refresh is fine for password rotation, tight for an
  incident requiring immediate credential rotation — there's a manual
  `kubectl annotate externalsecret` hook for forced refresh.

## Reactivation path (for future you)

1. Create the GSM entries:
   ```
   gcloud secrets create mirador-db-password --replication-policy=automatic
   echo -n "$NEW_PASSWORD" | gcloud secrets versions add mirador-db-password --data-file=-
   ```
2. Grant the cluster's WIF-backed service account `roles/secretmanager.secretAccessor`.
3. Move `deploy/external-secrets/*` into `deploy/kubernetes/base/external-secrets/`.
4. Add it to `base/kustomization.yaml` so Argo CD picks it up.
5. Delete the `kubectl create secret generic mirador-secrets` step
   from `.gitlab-ci.yml`.

## References

- <https://external-secrets.io/> — operator docs
- ADR-0007 — Workload Identity Federation (same auth flow)
- ADR-0015 — Argo CD (which is what deploys these CRDs)
