# ADR-0034 — CI memory budget + Testcontainers-heavy integration tests

- **Status**: Accepted
- **Date**: 2026-04-19
- **Related**: [ADR-0004](0004-local-ci-runner.md) (local CI runner,
  no SaaS quota), [ADR-0033](0033-playwright-e2e-in-kind-in-ci.md)
  (Playwright E2E in kind-in-CI)

## Context

The `integration-test` job spawns a full Testcontainers matrix on the
local `macbook-local` runner:

| Container | Image | Reserved memory |
|---|---|---|
| Postgres | `postgres:17-alpine` | ~300 MB |
| Kafka | `bitnami/kafka:4.1` | ~700 MB |
| Redis | `redis:7-alpine` | ~100 MB |
| Keycloak (Quarkus) | `quay.io/keycloak/keycloak:26.2.5` | ~900 MB |
| Spring Boot fork (Surefire) | `temurin:25-jre` | 1.8 GB (`-Xmx`) |
| GitLab runner + Docker overhead | — | ~300 MB |

Sum: **~4 GB peak**. The macbook-local runner's Docker Desktop VM is
tuned to 4 GB. Under load the host's OOM-killer fires on whichever
process is largest — most often the Keycloak Quarkus JVM during its
augmentation phase, which drags Surefire down and SIGKILLs the whole
`mvn verify` invocation (exit 137).

This first surfaced on pipeline #474 (Keycloak 180 s startup timeout),
then on #479 (same), then #482 (Maven JVM directly killed at -Xmx3g
because containers were starved), then #485 (same even with -Xmx
lowered) — four red main pipelines in a row, each attributed to a
different surface symptom but all rooted in the same memory-budget
arithmetic.

The short-term workaround shipped in the same batch as this ADR:

```yaml
integration-test:
  allow_failure: true   # temporary; tracked by ADR-0034
```

`allow_failure: true` is a loaded decision: it stops blocking merges
on a test class that *should* be green, which creates the risk of
regressions sliding in unnoticed. The rest of this ADR is about how
we recover the signal without paying for a bigger runner.

## Decision

**Accept `integration-test: allow_failure: true` short-term,
implement one of three long-term remediations within 4 weeks.**

The three candidates, in order of implementation cost:

### Option A — tag-gate Keycloak-heavy ITs, move to a weekly schedule

Add a JUnit 5 `@Tag("keycloak-heavy")` to `KeycloakAuthITest` and any
other IT that boots Keycloak, wire Surefire / Failsafe to exclude that
tag by default:

```xml
<plugin>
  <artifactId>maven-failsafe-plugin</artifactId>
  <configuration>
    <excludedGroups>keycloak-heavy</excludedGroups>
  </configuration>
</plugin>
```

Then add a second CI job `integration-test:keycloak` with
`rules: - if: $CI_PIPELINE_SOURCE == "schedule" && $NIGHTLY_KEYCLOAK`
that runs the excluded tag once a week. Push the `allow_failure: true`
off the MR-path job.

**Pro**: cheapest to implement (1 annotation + 2 CI lines).  
**Con**: the Keycloak coverage is no longer per-MR — a regression
specific to the OIDC path can slip in during the week.

### Option B — `Testcontainers.reuse = true` across test classes

Enable container reuse in `.testcontainers.properties` +
`withReuse(true)` on each `@Container` static field. On the second
IT class that needs Keycloak, Testcontainers finds the existing
container and skips startup. Peak memory drops because the four
containers are spawned *once per CI worker*, not once per class.

**Pro**: all tests keep running per-MR, signal preserved.  
**Con**: requires an up-front audit of every static `@Container`
field (+ Ryuk re-enable, which needs another host-network tweak on
Docker Desktop Mac per the runner config); non-trivial on a first
pass.

### Option C — raise the runner memory tier

Reconfigure Docker Desktop's VM memory from 4 GB to 6-8 GB (Docker
Desktop → Settings → Resources → Memory). Also bump the runner's
concurrent limit from 2 to 1 so one CI job never races another for
the pool.

**Pro**: zero code changes.  
**Con**: the developer's laptop runs slower during the day (less RAM
available to IDE / browsers / Docker Desktop itself); the runner
becomes a single-serialised lane, doubling queue time on busy days.

### Picked — start with A, revisit B if recurrence

Tag-gating (A) is the smallest step that restores the per-MR signal
on everything EXCEPT the OIDC path. If after one month the
scheduled `integration-test:keycloak` catches a regression that an
MR-gating pipeline would have caught earlier, graduate to Option B.
Option C is the last-resort fallback and is only taken if the
dev-laptop doubling as CI runner becomes untenable (noisy IDE,
slow compose restarts).

## Consequences

### Positive

- **Re-unblocks the MR pipeline** — no more red "integration-test"
  rows gating unrelated merges.
- **Keycloak coverage preserved** — just moved to a scheduled
  weekly run instead of per-MR.
- **Measured, tracked tech debt** — this ADR is the commit-log
  entry for "why is `allow_failure: true` on that job" that would
  otherwise haunt the CI yaml indefinitely.

### Negative

- **~7-day window** between Keycloak regressions being introduced
  and being caught. Mitigation: the `@SpringBootTest` loads the
  SecurityConfig on every MR, so a compile-error break is still
  caught per-MR; only *runtime OIDC semantics* regressions need the
  weekly schedule.
- **Two IT jobs instead of one** — marginal maintenance cost on the
  CI yaml.

### Neutral

- **Container reuse (Option B)** would be strictly better, but its
  up-front cost of auditing every `@Container` field + re-enabling
  Ryuk on Docker Desktop outweighs the benefit at the current IT
  count. Re-cost at 30+ IT classes.

## Alternatives considered

| Alternative | Why rejected |
|---|---|
| **Fight the OOM with `-Xmx` tuning alone** | Already tried (3g, 1800m). The problem is container memory OUTSIDE the Maven JVM; no JVM-heap tuning can fix host-level OOM. |
| **Use an arm64 Keycloak image to avoid emulation** | Keycloak 26 publishes multi-arch, but Quarkus startup cost is image-size-dominant, not arch-dominant. No measurable win. |
| **Split integration-test into per-package jobs** | Doubles CI yaml surface without fixing the root (each split job still spawns a Keycloak container if any test in its scope needs one). |
| **Rent a bigger GitLab SaaS runner** | Violates ADR-0004 (no paid SaaS quota). |
| **Mock Keycloak via WireMock** | Loses the point of testing against a real OIDC server. Keycloak integration bugs this spec caught historically (token-refresh races, role mapping) wouldn't surface against a WireMock stub. |

## Revisit this when

- A Keycloak regression slips past the weekly schedule → graduate to
  Option B.
- IT count crosses 30 classes → Option B pre-emptively.
- Docker Desktop raises its default VM memory to 6+ GB → Option C
  becomes free.
- The runner moves off the developer laptop (e.g. to a dedicated
  Mac mini) → Option C becomes free.
