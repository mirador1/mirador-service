# ADR-0007: Workload Identity Federation for GCP auth in CI

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

Before this decision, GitLab CI authenticated to GCP using a long-lived
service account key file stored as a masked CI variable. This has known
problems:

- Keys don't rotate unless someone remembers to rotate them. Nobody does.
- A leaked CI variable = indefinite GCP access until manually revoked.
- Google strongly discourages the pattern: SA keys are one of the top
  attack vectors in their 2023/2024 security posts.

## Decision

Use **Workload Identity Federation (WIF)**. The GitLab CI job requests a
short-lived OIDC token (JWT), which Google STS exchanges for a scoped
access token bound to a GCP service account. No key file ever touches
disk.

Configuration pieces:

- GCP side: Workload Identity Pool + Provider matching on `iss`
  (`https://gitlab.com`), `sub` (specific project path), and `aud`.
- GitLab side: `id_tokens:` block in each job needing GCP access.
- Bridge: a small JSON `credentials_source` file pointing at the OIDC
  token and at the impersonation endpoint.

## Consequences

### Positive

- No static credentials. Every token is short-lived (~1h).
- Revoking access = deleting the WIF provider or IAM binding.
- Auditable: every token exchange is logged in Cloud Audit Logs.
- Complies with GCP's own recommendations.

### Negative

- One-time setup complexity: ~20 minutes of GCP/GitLab config. The
  recipe is baked into `deploy/terraform/gcp/wif.tf`.
- Debugging a broken WIF binding is fiddly — the IAM error messages
  are generic ("failed to generate access token").

### Neutral

- Cost: free. WIF is not billed.

## Alternatives considered

### Alternative A — Long-lived SA key files

Rejected for the reasons above. The previous state of the repo used
this; migrating to WIF removed the key from the GitLab variable store
entirely.

### Alternative B — Short-lived keys (24h) rotated manually

Rejected: someone still has to rotate. WIF does this automatically per
request.

## References

- `deploy/terraform/gcp/wif.tf` — the WIF pool / provider / SA impersonation bindings.
- `.gitlab-ci.yml` — `id_tokens: GCP_OIDC_TOKEN` in jobs needing GCP.
- [GitLab OIDC + Google Cloud](https://docs.gitlab.com/ee/ci/cloud_services/google_cloud/)
