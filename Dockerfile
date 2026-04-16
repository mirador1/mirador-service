# =============================================================================
# Multi-stage Dockerfile — JVM runtime, non-root, OCI-labelled.
#
# Three stages:
#   1. builder  — compiles the project with Maven (full JDK, Maven wrapper)
#   2. layers   — extracts the Spring Boot layered JAR into separate directories
#   3. runtime  — minimal JRE image with only the application layers
#
# Runtime base choice — why eclipse-temurin:25-jre and not distroless?
#   Google distroless-java only ships up to Java 21 at the time of writing.
#   Our bytecode target is Java 25 (pom.xml → java.version=25), so
#   distroless-java21 would fail with UnsupportedClassVersionError. When
#   Google publishes distroless-java25, we migrate (tracked in TASKS.md).
#
# Image tagging strategy:
#   The CI pipeline tags each build with $CI_COMMIT_SHA + $CI_COMMIT_REF_SLUG
#   AND adds OCI image labels (org.opencontainers.image.*) for supply-chain
#   traceability. cosign signs the image by SHA digest, not tag.
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1 — build
# Uses the Maven wrapper (mvnw) so the Maven version is pinned in .mvn/wrapper/
# -q suppresses verbose Maven output; -DskipTests speeds up the image build.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Cap Maven/JVM heap to avoid OOM on constrained builders (Kaniko on GitLab
# SaaS runners default to ~2 GB total RAM; without this cap, Maven + JaCoCo
# + plugin classloaders can blow past the cgroup limit → exit 137).
# -T 1 forces single-threaded build to halve peak memory usage.
ENV MAVEN_OPTS="-Xmx512m -Xss512k -XX:+UseSerialGC"

# Copy Maven wrapper first so the dependency layer is cached independently
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Download all dependencies before copying source — this layer is only
# invalidated when pom.xml changes, not on every source file change.
RUN ./mvnw dependency:go-offline -q -T 1

COPY src/ src/
RUN ./mvnw package -DskipTests -q -T 1

# ---------------------------------------------------------------------------
# Stage 2 — extract layers                               [Spring Boot 3+]
# Spring Boot's layered JAR mode splits the fat JAR into:
#   - dependencies/         third-party libraries (stable, rarely changes)
#   - spring-boot-loader/   Spring Boot launcher classes
#   - snapshot-dependencies/ SNAPSHOT dependencies
#   - application/          compiled application classes + resources (changes often)
# Each becomes a separate Docker layer, enabling granular cache reuse.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS layers
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher

# ---------------------------------------------------------------------------
# Stage 3 — runtime (JRE-only, non-root, OCI-labelled)
# Only the JRE is needed at runtime — no compiler, no Maven, no source code.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# OCI image labels — read by cosign, Trivy, GitLab container registry UI,
# and the org.opencontainers spec tooling in general. These survive into
# the image manifest and are visible via `docker inspect` and GitLab's
# "Container Registry" tab without pulling the image. Concrete values
# (revision, created) are injected by the CI --label flags at build time.
LABEL org.opencontainers.image.title="mirador-service" \
      org.opencontainers.image.description="Mirador Spring Boot 4 / Java 25 backend" \
      org.opencontainers.image.source="https://gitlab.com/mirador1/mirador-service" \
      org.opencontainers.image.licenses="Proprietary" \
      org.opencontainers.image.vendor="mirador1" \
      org.opencontainers.image.base.name="eclipse-temurin:25-jre"

# Security: run as a dedicated non-root system user.
# If the application is compromised, the attacker cannot write to system directories.
RUN groupadd --system spring && useradd --system --gid spring spring

# Copy layers in order from least-frequently-changed to most-frequently-changed.
# This maximises Docker layer cache reuse: only the "application" layer is
# rebuilt on each code change.
#
# --chown ensures files are owned by the spring user — this matters because
# the K8s deployment mounts /tmp and /var/log/app as emptyDir volumes and
# needs the main process user to own them.
COPY --from=layers --chown=spring:spring /app/app/dependencies/ ./
COPY --from=layers --chown=spring:spring /app/app/spring-boot-loader/ ./
COPY --from=layers --chown=spring:spring /app/app/snapshot-dependencies/ ./
COPY --from=layers --chown=spring:spring /app/app/application/ ./

USER spring:spring

EXPOSE 8080

# JarLauncher is the Spring Boot launcher class — it handles the exploded layer structure.
# Do NOT use "java -jar app.jar" here since the JAR was extracted, not kept intact.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
