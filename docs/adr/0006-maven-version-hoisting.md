# ADR-0006: Hoist every Maven version into `<properties>`

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

Maven dependencies and plugins can declare their version inline. Over
time this causes drift:

- The same library appears at two different versions in two profiles.
- Upgrading requires grepping the whole `pom.xml` and hoping to find
  them all.
- Downstream analysis tools (Renovate, OWASP Dependency-Check report)
  can't easily point at a single source of truth.

## Decision

Every `<dependency>` and `<plugin>` version **must** live in the
top-level `<properties>` block as `<foo.version>…</foo.version>` and be
referenced via `${foo.version}`.

The **only** legitimate exception is `<parent><version>…</version>`
because Maven resolves the parent before evaluating properties.

A lefthook pre-commit hook (`pom-hardcoded-versions`) enforces this at
commit time — commit is rejected if any other hardcoded version sneaks
in.

## Consequences

### Positive

- Version bumps happen in one place.
- Renovate / Dependabot can track a single property per library.
- Cross-profile drift impossible — there's one variable.
- Lefthook catches violations before they merge.

### Negative

- `<properties>` block is now ~40 lines. Acceptable cost for the safety.

### Neutral

- Property names follow the convention `<artifact-id>.version`. When the
  artifactId has a common prefix (Maven plugins), we drop the prefix to
  keep names readable.

## Alternatives considered

### Alternative A — Status quo (versions inline)

Rejected: demonstrably drifted in practice. Upgrading Lombok used to
require two edits. This problem motivated the ADR.

### Alternative B — `pluginManagement` section + dependencyManagement
only, no explicit properties

Rejected: still allows inline versions in the actual `<dependency>`
declarations. Properties approach is cleaner and catches dependencies
too, not just plugins.

## References

- `pom.xml` — `<properties>` block at the top.
- `lefthook.yml` — `pom-hardcoded-versions` hook.
- ADR-0002 — related spirit: declarative centralisation.
