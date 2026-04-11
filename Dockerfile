# =============================================================================
# Multi-stage Dockerfile — JVM runtime (standard deployment)
#
# Three stages:
#   1. builder  — compiles the project with Maven (full JDK, Maven wrapper)
#   2. layers   — extracts the Spring Boot layered JAR into separate directories
#   3. runtime  — minimal JRE image with only the application layers
#
# Why three stages?
#   - The build environment (JDK + Maven ~700 MB) is discarded — the final image
#     contains only the JRE (~220 MB) and the application layers.
#   - Layered JARs improve Docker layer cache hits: the "dependencies" layer
#     (rarely changing) is cached separately from the "application" layer
#     (changes on every build), so pushes are faster after the first build.
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1 — build
# Uses the Maven wrapper (mvnw) so the Maven version is pinned in .mvn/wrapper/
# -q suppresses verbose Maven output; -DskipTests speeds up the image build.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

# Copy Maven wrapper first so the dependency layer is cached independently
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Download all dependencies before copying source — this layer is only
# invalidated when pom.xml changes, not on every source file change.
RUN ./mvnw dependency:go-offline -q

COPY src/ src/
RUN ./mvnw package -DskipTests -q

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
# Stage 3 — runtime (minimal image)
# Only the JRE is needed at runtime — no compiler, no Maven, no source code.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app

# Security: run as a dedicated non-root system user.
# If the application is compromised, the attacker cannot write to system directories.
RUN groupadd --system spring && useradd --system --gid spring spring

# Copy layers in order from least-frequently-changed to most-frequently-changed.
# This maximises Docker layer cache reuse: only the "application" layer is
# rebuilt on each code change.
COPY --from=layers /app/app/dependencies/ ./
COPY --from=layers /app/app/spring-boot-loader/ ./
COPY --from=layers /app/app/snapshot-dependencies/ ./
COPY --from=layers /app/app/application/ ./

USER spring

# JarLauncher is the Spring Boot launcher class — it handles the exploded layer structure.
# Do NOT use "java -jar app.jar" here since the JAR was extracted, not kept intact.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
