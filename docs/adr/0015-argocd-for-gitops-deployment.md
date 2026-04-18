# ADR-0015: Argo CD for GitOps deployment on GKE

- **Status**: Accepted
- **Date**: 2026-04-18

## Context

Before this decision, the `main`-branch pipeline in `.gitlab-ci.yml`
ran `deploy:gke` as its last stage, which did:

```
kubectl apply -k deploy/kubernetes/overlays/gke
```

This "CI pushes to the cluster" approach has three documented failure
modes that we've actually hit during this project:

1. **State drift is invisible**. When someone runs `kubectl edit` on the
   cluster (patching resources during a live incident), the next CI run
   re-applies the manifests and silently overwrites the edit — or
   worse, leaves the cluster in a hybrid state that nobody expected.
2. **Rollback is out-of-band**. Reverting a deploy means reverting a
   git commit, re-triggering the pipeline, and waiting for
   `docker-build` + `deploy:gke` to finish — 6-10 minutes minimum.
3. **CI needs cluster credentials**. The `deploy:gke` job needs a
   kubeconfig + `GCP_SERVICE_ACCOUNT` with RBAC to apply everything,
   so a CI-token compromise = cluster compromise.

The demo explicitly aims to illustrate "industry-standard" platform
practices (see ADR-0013, ADR-0014). Push-based CI-driven deploys are
no longer the reference pattern — they've been superseded by
GitOps in every major platform-engineering track (CNCF, Platform
Engineering Kit, OpenShift, GKE, EKS).

## Decision

**Argo CD pulls manifests from the git repository's `main` branch and
reconciles them onto the cluster continuously**, with `selfHeal=true`
and `prune=true`. The `deploy:gke` job in `.gitlab-ci.yml` becomes
vestigial after Argo CD is installed.

Concretely:
- Install the **Argo CD core subset** (server + repo-server +
  application-controller + redis — no ApplicationSet, Dex, or
  Notifications, which the demo doesn't need) with tight resource
  requests so it fits on the existing GKE Autopilot nodes (see
  ADR-0014).
- Apply a single `Application` CR (`deploy/argocd/application.yaml`)
  pointing at `main` + path `deploy/kubernetes/overlays/gke`.
- `syncPolicy.automated`: `selfHeal=true`, `prune=true`. Manual
  `kubectl edit` is no longer possible without Argo CD reverting it
  on the next sync (every 3 min by default).

## Alternatives considered

**Flux v2** (the main Argo CD competitor):
- Same GitOps semantics, lighter footprint on the cluster (no UI).
- **Against**: Argo CD has a first-class web UI and better
  observability into sync status, which matters for a demo. Flux's
  `flux get` CLI-only UX doesn't screenshot well on a portfolio page.

**Keep push-based deploys with Kustomize + ArgoCD Image Updater**:
- Would defer the GitOps switch and just automate image tags.
- **Against**: doesn't fix the three push-based issues above. Half-step
  costs almost as much to set up as the full step.

**GitLab Agent + Auto-DevOps**:
- Native GitLab feature, pulls manifests from the same repo.
- **Against**: locks the demo to GitLab. Argo CD works with GitHub,
  Bitbucket, any git source — the pattern is portable.

**Rancher Fleet / ArgoCD + Rollouts / Flagger**:
- Canary / progressive delivery on top of GitOps.
- **Against**: out of scope for a single-replica demo
  (ADR-0014). Recorded here as a future upgrade once traffic justifies
  canary.

## Consequences

Positive:
- Drift is caught in ~3 min. `kubectl edit` on the cluster is
  non-destructive in the long run because Argo CD restores the state
  from git.
- Rollback = `git revert <commit>`. Argo CD picks it up on next sync,
  no pipeline required.
- CI no longer needs write access to the cluster — the `deploy:gke`
  job can be retired. Argo CD uses its own cluster-scoped RBAC.
- The UI (`kubectl port-forward -n argocd svc/argocd-server 8080:443`)
  is a visible "state of the cluster" dashboard that pairs well with
  Grafana for the observability demo.

Negative:
- One more platform component to keep up to date (Argo CD itself).
  Mitigated by pinning to the `stable/manifests/install.yaml` channel
  and batching upgrades quarterly.
- The demo loses the ability to deploy directly from a feature branch.
  To test a change on the cluster, you merge to `main` or point Argo
  temporarily at a branch — documented in the Argo CD runbook.
- Extra resource budget on the cluster (~400 m CPU / ~900 Mi mem for
  the 4 Argo CD pods at the shrunk resource profile).

## Reference implementation

- `deploy/argocd/application.yaml` — the Application CR currently
  deployed on the `mirador-prod` GKE cluster.
- Install (one-time):
  ```
  kubectl create namespace argocd
  kubectl apply --server-side -n argocd \
    -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
  # Drop unused controllers (see ADR-0014)
  kubectl delete deploy argocd-applicationset-controller \
    argocd-dex-server argocd-notifications-controller -n argocd
  # Shrink resources (see ADR-0014 scaling playbook)
  kubectl apply -f deploy/argocd/application.yaml
  ```
- Admin password: read once with
  `kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d`.

## References

- <https://argo-cd.readthedocs.io/en/stable/> — Argo CD docs
- ADR-0014 — single-replica + resource-tight convention that made
  Argo CD fit on the current cluster
- ADR-0013 — in-cluster Postgres decision (same day, same scope reset
  that enabled the Argo CD adoption without a quota bump)
