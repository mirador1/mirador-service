# Stage 1 — build
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline -q
COPY src/ src/
RUN ./mvnw package -DskipTests -q

# Stage 2 — extract layers
FROM eclipse-temurin:25-jdk AS layers
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher

# Stage 3 — runtime (minimal image)
FROM eclipse-temurin:25-jre
WORKDIR /app
# Principle of least privilege: run as dedicated non-root user
RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=layers /app/app/dependencies/ ./
COPY --from=layers /app/app/spring-boot-loader/ ./
COPY --from=layers /app/app/snapshot-dependencies/ ./
COPY --from=layers /app/app/application/ ./
USER spring
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
