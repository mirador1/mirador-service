# ADR-0002: Kustomize over Helm for Kubernetes manifests

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

We need to deploy the same application to four targets — local kind, GKE
Autopilot (with Cloud SQL), AWS EKS, Azure AKS — with cluster-specific
variations:

- TLS + cert-manager annotations (GKE only)
- Cloud SQL Auth Proxy sidecar (GKE only — replaces in-cluster Postgres)
- `imagePullPolicy: Never` (kind only)

Previously each deploy job assembled its manifest set with a bash for-loop
and an assortment of `kubectl patch` calls, which was drift-prone and hard
to reason about.

## Decision

Use **Kustomize** (built into `kubectl` ≥ 1.14) with a `base/ + overlays/`
layout:

```
deploy/kubernetes/
  base/          ← shared manifests, HTTP-only, no cluster specifics
  base/postgres/ ← optional in-cluster Postgres mini-base
  overlays/
    local/       ← kind + in-cluster Postgres + imagePullPolicy Never
    gke/         ← Cloud SQL sidecar + cert-manager TLS
    eks/         ← in-cluster Postgres
    aks/         ← in-cluster Postgres
```

Each overlay is a `kustomization.yaml` that composes the base with
declarative strategic merge patches. CI simply runs:

```
kubectl kustomize deploy/kubernetes/overlays/$OVERLAY | envsubst | kubectl apply -f -
```

`envsubst` fills in `${K8S_HOST}`, `${IMAGE_TAG}`, etc. — Kustomize does
not do variable substitution, and we don't want Helm's templating engine.

## Consequences

### Positive

- Declarative: each overlay is a self-contained description of the target.
- No runtime templating engine. `kubectl kustomize` output is deterministic
  and can be committed as an artifact if auditors ask.
- Built-in to `kubectl` — no separate tool install, no cluster-side
  component (Helm's Tiller was historically a security concern; Helm 3
  removed it but the toolchain is still external).
- Strategic merge patches are easy to read and diff.

### Negative

- Variables (`${FOO}`) still need `envsubst` in the pipeline — Kustomize's
  own `configMapGenerator`/`secretGenerator` are less ergonomic for our
  substitution pattern.
- Kustomize's security model forbids referencing raw YAML files outside a
  kustomization tree: optional pieces (Postgres) must be wrapped in their
  own sub-base. This is a minor annoyance.

### Neutral

- Kustomize doesn't have a registry concept (Helm has ArtifactHub). We
  don't reuse third-party charts — all manifests are ours.

## Alternatives considered

### Alternative A — Helm charts

Rejected because:
1. Go-template interpolation (`{{ }}`) in YAML is brittle and ugly to diff.
2. Requires learning Helm-specific idioms (`values.yaml`, `_helpers.tpl`,
   release names, hooks).
3. One more tool to install and pin.
4. Strategic merge patches (Kustomize) are strictly more structured than
   conditional string templating (Helm).

### Alternative B — Jsonnet / CUE

Rejected: more powerful but much higher learning curve; overkill for a
single-service deployment with 4 overlays.

### Alternative C — Raw YAML + for-loop (status quo)

What we had. Rejected because drift between targets was real and
impossible to spot in review.

## References

- `deploy/kubernetes/README.md` — documents the concrete layout.
- [Kustomize docs](https://kubectl.docs.kubernetes.io/guides/introduction/kustomize/)
- Kelsey Hightower — [Why I prefer Kustomize](https://github.com/kelseyhightower/nocode) (satirical but widely cited)
