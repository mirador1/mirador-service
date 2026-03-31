#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "==> Starting upgrade in: $ROOT_DIR"

PKG_BASE="com/example/springapi"
JAVA_SRC="src/main/java/${PKG_BASE}"
RES_SRC="src/main/resources"
TEST_SRC="src/test/java/${PKG_BASE}"

mkdir -p "${JAVA_SRC}/config"
mkdir -p "${JAVA_SRC}/filter"
mkdir -p "${JAVA_SRC}/service"
mkdir -p "${JAVA_SRC}/controller"
mkdir -p "${JAVA_SRC}/health"
mkdir -p "${RES_SRC}"
mkdir -p ops/observability/grafana/provisioning/datasources
mkdir -p ops/observability/grafana/provisioning/dashboards
mkdir -p ops/observability/grafana/dashboards

backup_file() {
  local file="$1"
  if [ -f "$file" ]; then
    cp "$file" "$file.bak.$(date +%Y%m%d%H%M%S)"
  fi
}

echo "==> Backing up current key files"
backup_file pom.xml
backup_file README.md
backup_file "${RES_SRC}/application.properties"
backup_file "${RES_SRC}/application.yml"
backup_file "${JAVA_SRC}/controller/CustomerController.java"

echo "==> Writing pom.xml"
cat > pom.xml <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.5</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>springapi</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>springapi</name>
    <description>Demo Spring Boot project upgraded for observability and run-oriented positioning</description>

    <properties>
        <java.version>25</java.version>
    </properties>

    <dependencies>
        <!-- Core app -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Observability -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-opentelemetry</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- Docs -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>3.0.2</version>
        </dependency>

        <!-- DB -->
        <dependency>
            <groupId>org.postgresql</groupId>
            <artifactId>postgresql</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-core</artifactId>
        </dependency>

        <!-- Tests -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>jdbc</artifactId>
            <version>1.21.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>1.21.3</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>postgresql</artifactId>
            <version>1.21.3</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.14.0</version>
                <configuration>
                    <release>25</release>
                </configuration>
            </plugin>

            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
EOF

echo "==> Replacing application.properties with application.yml"
rm -f "${RES_SRC}/application.properties"

cat > "${RES_SRC}/application.yml" <<'EOF'
spring:
  application:
    name: springapi
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/demo}
    username: ${DB_USERNAME:demo}
    password: ${DB_PASSWORD:demo}
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        format_sql: false
  flyway:
    enabled: true

server:
  port: ${SERVER_PORT:8080}
  error:
    include-stacktrace: never

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics,env,configprops
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}

logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} level=%-5level app=%property{spring.application.name} traceId=%X{traceId:-} spanId=%X{spanId:-} requestId=%X{requestId:-} thread=%thread logger=%logger{36} - %msg%n"
  level:
    root: INFO
    org.springframework.web: INFO
    org.hibernate.SQL: WARN
    org.hibernate.orm.jdbc.bind: WARN

app:
  observability:
    recent-buffer-gauge-enabled: true
EOF

echo "==> Writing RequestIdFilter"
cat > "${JAVA_SRC}/filter/RequestIdFilter.java" <<'EOF'
package com.example.springapi.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER_NAME);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER_NAME, requestId);

        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            logger.info("http_access method={} uri={} status={} durationMs={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);
            MDC.remove(MDC_KEY);
        }
    }
}
EOF

echo "==> Writing ObservabilityConfig"
cat > "${JAVA_SRC}/config/ObservabilityConfig.java" <<'EOF'
package com.example.springapi.config;

import com.example.springapi.service.RecentCustomerBuffer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    @Bean
    Gauge recentCustomerBufferGauge(MeterRegistry registry, RecentCustomerBuffer recentCustomerBuffer) {
        return Gauge.builder("customer.recent.buffer.size", recentCustomerBuffer, buffer -> {
                    try {
                        return buffer.getRecent().size();
                    } catch (Exception ex) {
                        return 0;
                    }
                })
                .description("Current size of the recent customer in-memory buffer")
                .register(registry);
    }
}
EOF

echo "==> Writing custom health indicator"
cat > "${JAVA_SRC}/health/DatabaseReachabilityHealthIndicator.java" <<'EOF'
package com.example.springapi.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("dbReachability")
public class DatabaseReachabilityHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseReachabilityHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
            if (result != null && result == 1) {
                return Health.up()
                        .withDetail("database", "reachable")
                        .build();
            }
            return Health.down()
                    .withDetail("database", "unexpected response")
                    .build();
        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("database", "unreachable")
                    .build();
        }
    }
}
EOF

echo "==> Writing CustomerController with metrics + observations"
cat > "${JAVA_SRC}/controller/CustomerController.java" <<'EOF'
package com.example.springapi.controller;

import com.example.springapi.dto.CreateCustomerRequest;
import com.example.springapi.dto.CustomerDto;
import com.example.springapi.service.AggregationService;
import com.example.springapi.service.CustomerService;
import com.example.springapi.service.RecentCustomerBuffer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService service;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final AggregationService aggregationService;
    private final ObservationRegistry observationRegistry;
    private final Counter customerCreatedCounter;
    private final Timer customerCreateTimer;
    private final Timer customerFindAllTimer;
    private final Timer customerAggregateTimer;

    public CustomerController(CustomerService service,
                              RecentCustomerBuffer recentCustomerBuffer,
                              AggregationService aggregationService,
                              ObservationRegistry observationRegistry,
                              MeterRegistry meterRegistry) {
        this.service = service;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.aggregationService = aggregationService;
        this.observationRegistry = observationRegistry;
        this.customerCreatedCounter = Counter.builder("customer.created.count")
                .description("Number of customers created")
                .register(meterRegistry);
        this.customerCreateTimer = Timer.builder("customer.create.duration")
                .description("Duration of customer creation requests")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.customerFindAllTimer = Timer.builder("customer.find_all.duration")
                .description("Duration of customer list requests")
                .publishPercentileHistogram()
                .register(meterRegistry);
        this.customerAggregateTimer = Timer.builder("customer.aggregate.duration")
                .description("Duration of aggregate endpoint")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    @GetMapping
    public List<CustomerDto> getAll() {
        return Observation.createNotStarted("customer.find-all", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .observe(() -> customerFindAllTimer.record(service::findAll));
    }

    @PostMapping
    public CustomerDto create(@Valid @RequestBody CreateCustomerRequest request) {
        return Observation.createNotStarted("customer.create", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .observe(() -> customerCreateTimer.record(() -> {
                    CustomerDto result = service.create(request);
                    customerCreatedCounter.increment();
                    return result;
                }));
    }

    @GetMapping("/recent")
    public List<CustomerDto> getRecent() {
        return Observation.createNotStarted("customer.recent", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/recent")
                .observe(recentCustomerBuffer::getRecent);
    }

    @GetMapping("/aggregate")
    public AggregationService.AggregatedResponse aggregate() {
        long start = System.nanoTime();
        try {
            return Observation.createNotStarted("customer.aggregate", observationRegistry)
                    .lowCardinalityKeyValue("endpoint", "/customers/aggregate")
                    .observe(aggregationService::aggregate);
        } finally {
            long duration = System.nanoTime() - start;
            customerAggregateTimer.record(duration, TimeUnit.NANOSECONDS);
        }
    }
}
EOF

echo "==> Writing README.md"
cat > README.md <<'EOF'
# spring-4-demo

Mini service Spring Boot 4 / Java 25 utilisé comme démonstrateur technique orienté :

- structuration de socle applicatif
- observabilité
- qualité d’exploitation
- traçabilité des requêtes
- instrumentation métriques / traces
- lisibilité de choix techniques

## Ce que ce projet démontre

Ce projet n’a pas été pensé comme un simple CRUD de démonstration.
L’objectif est de montrer la capacité à :

- reprendre un socle Spring Boot moderne
- le rendre exploitable et observable
- expliciter les choix de supervision
- définir des points de diagnostic rapides
- instrumenter un service avec métriques et traces
- fournir des artefacts utiles en contexte RUN / support / pilotage technique

## Stack

- Java 25
- Spring Boot 4
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- Flyway
- Springdoc / OpenAPI
- Actuator
- Micrometer + Prometheus
- OpenTelemetry (OTLP)
- Docker / Docker Compose
- Testcontainers

## Endpoints métier

- `GET /customers`
- `POST /customers`
- `GET /customers/recent`
- `GET /customers/aggregate`

## Endpoints d’exploitation

- `GET /actuator/health`
- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`
- `GET /actuator/prometheus`
- `GET /actuator/metrics`

## Choix d’observabilité

### 1. Corrélation des requêtes
Chaque requête HTTP reçoit un `X-Request-Id` :
- réutilisé si fourni par le client
- généré sinon
- renvoyé dans la réponse
- injecté dans les logs

### 2. Métriques exposées
Exemples :
- `customer.created.count`
- `customer.create.duration`
- `customer.find_all.duration`
- `customer.aggregate.duration`
- `customer.recent.buffer.size`

### 3. Tracing
Le projet exporte les traces en OTLP.
En local, on peut brancher un backend OTLP pour visualiser les spans.

### 4. Health checks
Le projet expose :
- health globale
- liveness
- readiness
- un check DB simple (`select 1`)

## Démarrage local

### Base de données
Le projet suppose PostgreSQL local :

- host: `localhost`
- port: `5432`
- db: `demo`
- user: `demo`
- password: `demo`

### Lancer l’application
```bash
./mvnw spring-boot:run