# -------- BUILD STAGE --------
FROM maven:3.9.14-eclipse-temurin-25 AS builder

WORKDIR /app

# cache dépendances
COPY pom.xml .
RUN mvn -B -q -e -DskipTests dependency:go-offline

# copie code
COPY src ./src

# build
RUN mvn -B -DskipTests package


# -------- RUNTIME STAGE --------
FROM eclipse-temurin:25-jre

WORKDIR /app

# copie du jar depuis le builder
COPY --from=builder /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","app.jar"]