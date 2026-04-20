# ADR-0037 — Spectral `oas3-valid-*-example` rules disabled (temporary)

- Status: Accepted (temporary, revisit on springdoc bump)
- Date: 2026-04-20
- Deciders: @benoit.besson
- Related: ADR-0013 (OpenAPI contract), ADR-0034 (CI memory budget)

## Context

Our CI runs `@stoplight/spectral-cli@6.15.1` against `/v3/api-docs` (the
springdoc-generated OpenAPI document) in the `openapi-lint` job. Two rules
from the built-in `spectral:oas` preset consistently flag findings we
cannot fix at the annotation level without invasive code changes:

### Rule 1: `oas3-valid-schema-example`

Flags ~24 errors per run. Springdoc auto-generates `default: {}` for every
DTO and type-mismatched default values on primitives (`id: 0` where schema
is non-integer, `createdAt: "string"` where schema is `date-time`, etc.).
Example finding:

```
components.schemas.CustomerDto.properties.id.default
  "default" property type must be integer
```

Root cause: springdoc-openapi's `ModelConverter` emits defaults derived
from Java field initializers. Without an explicit `@Schema(defaultValue=…)`
override on every DTO field, the defaults are lossy and Spectral catches
them correctly — they ARE invalid per OpenAPI 3 strict reading.

### Rule 2: `oas3-valid-media-example`

Flags 10+ errors on path parameter `example`s. Every `@PathVariable Long id`
annotated with `@Parameter(example = "42")` produces an openapi.json
entry where the example is emitted at the parameter level as a string,
but Spectral validates it against the inferred integer schema and
reports:

```
paths./customers/{id}.get.parameters[0].example
  "example" property type must be string
```

## Decision

Disable both rules in `.spectral.yaml`. Document the 2 fix paths and
revisit on the next springdoc-openapi version bump.

## Fix paths (for a future session to pick up)

### Path A: explicit `@Schema` annotations on every DTO + parameter

**Cost**: ~50 annotations across `customer/`, `audit/`, `observability/`.
For each DTO field:

```java
@Schema(defaultValue = "null", example = "\"Alice\"")
private String name;
```

For each `@PathVariable`:

```java
@Parameter(description = "Customer ID", example = "42", schema = @Schema(type = "integer"))
@PathVariable Long id
```

**Pros**: fixes at the source, no runtime post-processing, reviewer sees
the example in the source.

**Cons**: verbose, easy to forget on new DTOs, drift.

### Path B: `OpenApiCustomizer` bean post-processes the generated spec

Register a `@Bean OpenApiCustomizer` that walks the `OpenAPI` tree and:

- Strips `default: {}` from every schema where the default isn't a valid
  instance of the schema's type.
- Normalises path-parameter `example` values to match the inferred schema
  type (or moves them under `schema.example`).

**Pros**: one central fix, no per-DTO annotation churn, new DTOs covered
automatically.

**Cons**: runtime dependency on the shape of springdoc's output; a
springdoc upgrade can change the tree and break the customizer silently.

### Path C: wait for springdoc-openapi upstream fix

The issue has been reported upstream multiple times over the past ~2
years. A future release (≥ 2.10.x) may finally emit examples at the
correct level. If Path A and B both look costly, pinning a specific
springdoc version and waiting for upstream is acceptable for this
portfolio project.

## Consequences

**Positive**:
- Pipeline stays green today without invasive code changes.
- Documented decision means the next session knows what's silent vs what's
  a genuine drift.

**Negative**:
- We lose coverage of 2 useful Spectral rules. Future regressions in the
  OpenAPI spec (wrong example types, invalid defaults) go undetected.
- Technical debt until a future session picks Path A or B.

## Revisit criteria

- Next springdoc-openapi minor version bump → retry with Path C.
- Any production incident tracing back to an undocumented default or
  wrong-typed example → forces Path A or B immediately.
- Quarterly backlog grooming — re-evaluate cost/benefit.

## References

- `.spectral.yaml` — rule overrides with inline comments pointing here.
- ADR-0013 — OpenAPI contract methodology.
- [Spectral `oas3-valid-schema-example` docs](https://docs.stoplight.io/docs/spectral/4dec24461f3af-open-api-rules#oas3-valid-schema-example)
- [springdoc-openapi issues tagged "example"](https://github.com/springdoc/springdoc-openapi/issues?q=label%3Aexample)
