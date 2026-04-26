# ADR-0039 — moved to `mirador-service-shared`

**Status** : Moved

This ADR's canonical version lives in the upstream submodule :

- **Canonical path** : [`mirador-service-shared/docs/adr/0039-two-observability-deployment-modes.md`](https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0039-two-observability-deployment-modes.md)
- **Local access via submodule** : `infra/shared/docs/adr/0039-two-observability-deployment-modes.md`
- **Title** : Two observability deployment modes (OTel-native vs Prometheus-community)

This file is a stub kept solely to preserve cross-references in this
repo (any link to `docs/adr/0039-two-observability-deployment-modes.md` from other files in
mirador-service-java continues to resolve here). The actual content
was deleted on 2026-04-26 to enforce a single canonical version per ADR.

**Do not edit this stub.** Edit the canonical ADR in the upstream repo,
push it, then bump the submodule SHA in this repo (`infra/shared/`).

## Why this exists

Per the 2026-04-26 cross-repo ADR audit, this ADR was duplicated between
mirador-service-java/docs/adr/ and mirador-service-shared/docs/adr/
with identical content. Maintaining 2 copies invited drift (one repo
edits, the other doesn't). The deletion + stub pattern :

- Keeps all existing cross-references in this repo working (the stub
  resolves to a file at the same path).
- Removes the drift surface (the stub has no factual content to drift).
- Points readers to the single source of truth.

See [common ADR-0001](https://gitlab.com/mirador1/mirador-common/-/blob/main/docs/adr/0001-shared-repo-via-submodule.md)
for the underlying submodule pattern + [common ADR-0060](https://gitlab.com/mirador1/mirador-common/-/blob/main/docs/adr/0060-flat-vs-transitive-submodule-inheritance.md)
for the α flat 2-submodule architecture.
