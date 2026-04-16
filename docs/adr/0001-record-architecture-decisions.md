# ADR-0001: Record architecture decisions

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

The repo has accumulated non-obvious architectural decisions (Kustomize
over Helm, Cloud SQL over in-cluster Postgres on GKE, local CI runner
only, feature-sliced packages, etc.) that live today only as tribal
knowledge or buried in commit messages. New contributors — human or
Claude sessions — have no way to discover *why* things are the way they
are, which leads to either:

1. Relitigating decisions already settled, or
2. Diverging from established patterns by accident.

## Decision

Adopt the **Michael Nygard ADR format** in `docs/adr/`. One file per
decision, numbered sequentially (`NNNN-kebab-title.md`). A new ADR is
required whenever a decision is:

- Introducing a new tool / service / dependency at the infrastructure level
- Changing a public API or integration contract
- Replacing one approach with another
- Locking in a constraint that limits future choices

Existing tacit decisions are retro-documented as ADRs 0002-0008.

## Consequences

### Positive

- Decisions become greppable. `docs/adr/` is the single source of truth.
- Superseded decisions are kept with a "Superseded by" pointer, preserving
  institutional memory instead of deleting it.
- New contributors can read the ADR index in ~20 minutes and understand
  the constraints.

### Negative

- Small overhead: each non-trivial decision requires writing an ADR.

### Neutral

- Only architectural decisions qualify. Code style / typo fixes / library
  patch bumps do not.

## Alternatives considered

### Alternative A — Keep decisions in the wiki

Rejected: wikis drift from the code, have no PR review, and are invisible
to tools that operate on the repo.

### Alternative B — RFC-style longer documents in `docs/rfc/`

Rejected: heavyweight format is discouraging. Most ADRs are one page.
RFCs are appropriate for proposals that need cross-team buy-in before
coding; ADRs capture the decision *after* it's made.

## References

- Michael Nygard — [Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
- [adr.github.io](https://adr.github.io/) — format catalog
