# ADR-0009: Container runtime base image — `eclipse-temurin:25-jre`

- **Status**: Accepted
- **Date**: 2026-04-16

## Context

The backend targets Java 25 (`pom.xml` → `<java.version>25</java.version>`;
class-file major 69). The runtime image in `Dockerfile` must:

1. Run Java 25 bytecode without triggering `UnsupportedClassVersionError`.
2. Stay small enough that image pull on a cold GKE Autopilot node is
   not the longest leg of a rollout.
3. Have a clean supply-chain story (SBOM friendly, CVE-scannable,
   cosign-signable without jumping through Alpine-musl quirks).
4. Run as non-root.

The obvious candidates were Google's `distroless`, `eclipse-temurin`
(Adoptium), `amazoncorretto`, and `alpine-jre` custom builds.

## Decision

Final stage of the multi-stage `Dockerfile` runs on
`eclipse-temurin:25-jre`. Builder and layer-extract stages use
`eclipse-temurin:25-jdk`. The application runs as a dedicated non-root
user (`spring:spring`) created in the runtime stage. No shell is
removed, but the default `ENTRYPOINT` bypasses it (`java -cp … JarLauncher`).

## Consequences

### Positive

- **Java 25 support out of the box.** Eclipse Adoptium ships Temurin 25
  images from day one; distroless-java is still at 21 at the time of
  writing (see migration note below).
- **Known provenance.** Temurin is TCK-certified and built by the Eclipse
  Foundation — a defensible supply chain.
- **Spring Boot layered JAR.** Stage 2 extracts the layered JAR so the
  runtime image layers (`dependencies/`, `spring-boot-loader/`,
  `snapshot-dependencies/`, `application/`) are cache-friendly for
  incremental deploys.
- **Debuggable.** A shell is available when a live pod exec is needed —
  valuable for on-call triage. Distroless removes that escape hatch.

### Negative

- **Larger CVE surface than distroless.** Ubuntu base has dozens of
  packages distroless does not. Partially offset by:
  - Grype / Trivy scans gate the build (`cvss >= 9` blocks).
  - OCI labels + cosign signing pin the exact image digest in
    production manifests.
- **Image size ~200 MB vs distroless ~80 MB.** Pulls are ~3s longer on
  a cold node. Acceptable — we optimise the startup probe budget for
  JVM warmup, not image pull.

### Neutral

- The multi-stage build caches `dependency:go-offline` in a dedicated
  layer (`RUN ./mvnw dependency:go-offline`) so only `src/` changes
  invalidate the expensive Maven resolution layer.

## Alternatives considered

### Alternative A — `gcr.io/distroless/java21-debian12`

**Rejected today.** Google distroless stops at Java 21; running Java 25
bytecode on a Java 21 runtime fails with `UnsupportedClassVersionError`
at startup. A migration is planned (tracked in `TASKS.md` — "distroless
java25 image") once Google publishes `distroless/java25`. The expected
gain is ~90 fewer CVEs and a ~40% smaller image.

### Alternative B — `amazoncorretto:25-alpine`

Rejected: Alpine's musl-libc has a history of glibc-incompatible edge
cases (DNS resolver, locale data) that bite intermittently in
production. Temurin's Ubuntu base avoids the class of problem entirely.

### Alternative C — Google Jib plugin, no Dockerfile

Rejected: Jib produces reproducible images but takes layer control away
from us. The explicit Dockerfile keeps the build-chain visible in
`docker build` + reviewable in diff, which matters for supply-chain
audits.

### Alternative D — GraalVM Native Image (`Dockerfile.native`)

Kept as a parallel artifact (`Dockerfile.native`) for experimental use,
not the primary runtime. Tradeoffs captured elsewhere: native images
rule out reflection-heavy libraries (Flyway, some Spring Boot
integrations) without `reachability-metadata` configuration, and the
build takes 5-10× longer. Not worth it for a service where cold-start
is dominated by Flyway + Spring context warmup, not JVM init.

## References

- `Dockerfile` — all three stages with inline rationale for each `FROM`.
- `Dockerfile.native` — experimental GraalVM variant.
- `TASKS.md` → "distroless java25 image" — tracks the future migration.
- [ADR-0008](0008-feature-sliced-packages.md) (package layout) — unrelated but
  referenced from the image's OCI labels.
- [Eclipse Temurin release cadence](https://adoptium.net/temurin/releases/).
