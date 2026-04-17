# External Secrets Operator — scaffolding

**Not yet applied to any cluster.** This directory holds the ExternalSecret
CRDs that replace the `kubectl create secret generic mirador-secrets …`
loop in `.kubectl-apply` once External Secrets Operator (ESO) is
installed on the cluster.

Kept OUTSIDE `deploy/kubernetes/base/` on purpose — the base Kustomize
build runs in CI every push and would fail on the unknown
`external-secrets.io` CRDs (they're only valid after ESO is installed).
Once ESO is deployed, move these files under
`deploy/kubernetes/base/external-secrets/` and reference them from the
base `kustomization.yaml`.

Tracking task: see `TASKS.md` → "External Secrets Operator + Google
Secret Manager".

## Files

| File | Role |
| --- | --- |
| [`secret-store.yaml`](secret-store.yaml) | `SecretStore` resource telling ESO how to reach Google Secret Manager using the backend's Workload Identity. |
| [`external-secret.yaml`](external-secret.yaml) | `ExternalSecret` resource materialising the `mirador-secrets` K8s Secret from specific Secret Manager keys. |

## Why this exists

Current state — secrets live in GitLab CI variable storage and are
written to the cluster by CI:

```
.gitlab-ci.yml               GitLab CI vars (DB_PASSWORD, JWT_SECRET, …)
        │
        ▼
kubectl create secret generic mirador-secrets --from-literal=…
```

Target state — secrets live in GCP Secret Manager and ESO syncs them
into the cluster:

```
GCP Secret Manager (mirador/db_password, …)
        │  (Workload Identity — no keys)
        ▼
External Secrets Operator
        │  (reconciliation loop)
        ▼
Secret/mirador-secrets
```

## Cutover procedure

1. **Install ESO** in the cluster:
   ```bash
   helm repo add external-secrets https://charts.external-secrets.io
   helm install external-secrets external-secrets/external-secrets \
     -n external-secrets-system --create-namespace
   ```
2. **Create the GCP secrets** (once):
   ```bash
   for k in db_password jwt_secret api_key otel_auth gitlab_token; do
     gcloud secrets create mirador-$k --replication-policy=automatic
     printf 'PLACEHOLDER' | gcloud secrets versions add mirador-$k --data-file=-
   done
   ```
3. **Grant the ESO Kubernetes service account access to the secrets**:
   ```bash
   gcloud secrets add-iam-policy-binding mirador-db_password \
     --member="serviceAccount:$GCP_PROJECT.svc.id.goog[external-secrets-system/external-secrets]" \
     --role=roles/secretmanager.secretAccessor
   # (repeat for each secret)
   ```
4. **Move these files** into the base overlay and wire them in:
   ```bash
   mkdir -p deploy/kubernetes/base/external-secrets
   mv deploy/external-secrets/*.yaml deploy/kubernetes/base/external-secrets/
   # then in deploy/kubernetes/base/kustomization.yaml add:
   #   resources:
   #     - external-secrets/secret-store.yaml
   #     - external-secrets/external-secret.yaml
   ```
5. **Remove the `kubectl create secret generic mirador-secrets`
   step** in `.gitlab-ci.yml`'s `.kubectl-apply` — ESO now owns that
   Secret.

After this, rotating a credential is:

```bash
printf 'new-value' | gcloud secrets versions add mirador-db_password --data-file=-
```

ESO pushes the new value into the cluster within `refreshInterval` (1 h by default).
