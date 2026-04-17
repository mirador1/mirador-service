# Argo CD — GitOps scaffolding

This directory only contains **scaffolding**. No Argo CD install is
deployed yet; the [pending task in `TASKS.md`](../../TASKS.md) tracks
the cutover.

## Files

| File | Purpose |
| --- | --- |
| [`application.yaml`](application.yaml) | The Argo CD `Application` CR pointing at `deploy/kubernetes/overlays/gke`. Apply once Argo CD is installed. |

## Cutover procedure

1. **Install Argo CD in the target cluster** (see `application.yaml`
   header for the exact `kubectl apply` command).
2. **Apply `application.yaml`**:
   ```bash
   kubectl apply -f deploy/argocd/application.yaml
   ```
3. **Remove or disable the `deploy:gke` job** in `.gitlab-ci.yml` so CI
   doesn't race Argo CD on reconciliation.
4. **Rename** this file's `targetRevision: main` to any branch you
   want Argo CD to track (production should stay on `main`).
5. **Verify** with `argocd app get mirador` and the Argo CD UI.

## Rollback

With Argo CD in place, rollback is just:

```bash
git revert <bad-sha>
git push origin main
```

No `kubectl` needed. Argo CD reconciles to the new main in ~30 s.

## Why pull-based beats push-based

- **Least-privilege**: Argo CD has cluster-admin in the cluster it
  runs in; the CI runner only needs image-registry push. Removes the
  long-lived GCP Workload Identity from CI.
- **Drift detection**: if someone `kubectl edit` on the cluster,
  `selfHeal: true` reverts it automatically.
- **Auditable**: every change to cluster state has a git commit.
  `kubectl apply` from CI is at best a CI log line.
- **Single source of truth**: cluster state = `deploy/kubernetes/overlays/gke`
  at the latest commit on `main`. Today, cluster state = whatever the
  last pipeline happened to run.
