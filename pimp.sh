#!/bin/bash

set -euo pipefail

PACKAGE_NAME="com.example.springapi"
PACKAGE_PATH=$(printf '%s' "$PACKAGE_NAME" | tr '.' '/')

BASE_DIR="${BASE_DIR:-.}"
SRC_MAIN_JAVA="$BASE_DIR/src/main/java/$PACKAGE_PATH"
SRC_MAIN_RESOURCES="$BASE_DIR/src/main/resources"
POM_FILE="$BASE_DIR/pom.xml"

echo ">>> Vérification structure"
mkdir -p "$SRC_MAIN_JAVA"
mkdir -p "$SRC_MAIN_RESOURCES"
mkdir -p "$SRC_MAIN_JAVA/controller"
mkdir -p "$SRC_MAIN_JAVA/service"
mkdir -p "$SRC_MAIN_JAVA/dto"
mkdir -p "$SRC_MAIN_JAVA/api"
mkdir -p "$SRC_MAIN_JAVA/context"

if [ ! -f "$POM_FILE" ]; then
  echo "ERREUR: pom.xml introuvable dans $BASE_DIR"
  exit 1
fi

echo ">>> Génération des nouvelles classes"

cat > "$SRC_MAIN_JAVA/dto/CustomerDto.java" <<EOF
package $PACKAGE_NAME.dto;

public record CustomerDto(Long id, String name, String email) {
}
EOF

cat > "$SRC_MAIN_JAVA/dto/CreateCustomerRequest.java" <<EOF
package $PACKAGE_NAME.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateCustomerRequest(
        @NotBlank String name,
        @NotBlank @Email String email
) {
}
EOF

cat > "$SRC_MAIN_JAVA/api/ApiError.java" <<EOF
package $PACKAGE_NAME.api;

public record ApiError(String code, String message) {
}
EOF

cat > "$SRC_MAIN_JAVA/api/ApiExceptionHandler.java" <<EOF
package $PACKAGE_NAME.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handle(Exception ex) {
        return switch (ex) {
            case MethodArgumentNotValidException e ->
                    ResponseEntity.badRequest()
                            .body(new ApiError("VALIDATION_ERROR", "Requête invalide"));
            case ConstraintViolationException e ->
                    ResponseEntity.badRequest()
                            .body(new ApiError("CONSTRAINT_VIOLATION", e.getMessage()));
            case IllegalArgumentException e ->
                    ResponseEntity.badRequest()
                            .body(new ApiError("BAD_REQUEST", e.getMessage()));
            default ->
                    ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ApiError("INTERNAL_ERROR", "Erreur interne"));
        };
    }
}
EOF

cat > "$SRC_MAIN_JAVA/context/RequestContext.java" <<EOF
package $PACKAGE_NAME.context;

public final class RequestContext {

    private RequestContext() {
    }

    public static final ScopedValue<String> REQUEST_ID = ScopedValue.newInstance();
}
EOF

cat > "$SRC_MAIN_JAVA/service/TraceService.java" <<EOF
package $PACKAGE_NAME.service;

import $PACKAGE_NAME.context.RequestContext;
import org.springframework.stereotype.Service;

@Service
public class TraceService {

    public String currentRequestIdOrDefault() {
        return RequestContext.REQUEST_ID.orElse("no-request-id");
    }
}
EOF

cat > "$SRC_MAIN_JAVA/service/RecentCustomerBuffer.java" <<EOF
package $PACKAGE_NAME.service;

import $PACKAGE_NAME.dto.CustomerDto;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.SequencedCollection;

@Service
public class RecentCustomerBuffer {

    private final LinkedList<CustomerDto> recent = new LinkedList<>();

    public synchronized void add(CustomerDto dto) {
        recent.addFirst(dto);
        if (recent.size() > 10) {
            recent.removeLast();
        }
    }

    public synchronized List<CustomerDto> getRecent() {
        SequencedCollection<CustomerDto> view = recent;
        return List.copyOf(view);
    }
}
EOF

cat > "$SRC_MAIN_JAVA/service/AggregationService.java" <<EOF
package $PACKAGE_NAME.service;

import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

@Service
public class AggregationService {

    public AggregatedResponse aggregate() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var customerFuture = executor.submit(this::loadCustomerData);
            var statsFuture = executor.submit(this::loadStats);

            return new AggregatedResponse(
                    customerFuture.get(),
                    statsFuture.get()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interruption", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Erreur d'agrégation", e);
        }
    }

    private String loadCustomerData() throws InterruptedException {
        Thread.sleep(200);
        return "customer-data";
    }

    private String loadStats() throws InterruptedException {
        Thread.sleep(200);
        return "stats";
    }

    public record AggregatedResponse(String customerData, String stats) {
    }
}
EOF

cat > "$SRC_MAIN_JAVA/service/CustomerService.java" <<EOF
package $PACKAGE_NAME.service;

import $PACKAGE_NAME.dto.CreateCustomerRequest;
import $PACKAGE_NAME.dto.CustomerDto;
import $PACKAGE_NAME.model.Customer;
import $PACKAGE_NAME.repository.CustomerRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final RecentCustomerBuffer recentCustomerBuffer;

    public CustomerService(CustomerRepository repository, RecentCustomerBuffer recentCustomerBuffer) {
        this.repository = repository;
        this.recentCustomerBuffer = recentCustomerBuffer;
    }

    public List<CustomerDto> findAll() {
        return repository.findAll()
                .stream()
                .map(this::toDto)
                .toList();
    }

    public CustomerDto create(CreateCustomerRequest request) {
        Customer customer = new Customer();
        customer.setName(request.name());
        customer.setEmail(request.email());

        Customer saved = repository.save(customer);
        CustomerDto dto = toDto(saved);
        recentCustomerBuffer.add(dto);
        return dto;
    }

    private CustomerDto toDto(Customer customer) {
        return new CustomerDto(
                customer.getId(),
                customer.getName(),
                customer.getEmail()
        );
    }
}
EOF

cat > "$SRC_MAIN_JAVA/controller/CustomerController.java" <<EOF
package $PACKAGE_NAME.controller;

import $PACKAGE_NAME.context.RequestContext;
import $PACKAGE_NAME.dto.CreateCustomerRequest;
import $PACKAGE_NAME.dto.CustomerDto;
import $PACKAGE_NAME.service.AggregationService;
import $PACKAGE_NAME.service.CustomerService;
import $PACKAGE_NAME.service.RecentCustomerBuffer;
import $PACKAGE_NAME.service.TraceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService service;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final AggregationService aggregationService;
    private final TraceService traceService;

    public CustomerController(
            CustomerService service,
            RecentCustomerBuffer recentCustomerBuffer,
            AggregationService aggregationService,
            TraceService traceService
    ) {
        this.service = service;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.aggregationService = aggregationService;
        this.traceService = traceService;
    }

    @GetMapping
    public List<CustomerDto> getAll() {
        return service.findAll();
    }

    @PostMapping
    public CustomerDto create(@Valid @RequestBody CreateCustomerRequest request) {
        return service.create(request);
    }

    @GetMapping("/recent")
    public List<CustomerDto> getRecent() {
        return recentCustomerBuffer.getRecent();
    }

    @GetMapping("/aggregate")
    public AggregationService.AggregatedResponse aggregate() {
        return aggregationService.aggregate();
    }

    @GetMapping("/trace-demo")
    public String traceDemo(@RequestHeader(name = "X-Request-Id", required = false) String requestId) throws Exception {
        String effectiveRequestId = (requestId == null || requestId.isBlank()) ? "req-local-demo" : requestId;
        return ScopedValue.where(RequestContext.REQUEST_ID, effectiveRequestId)
                .call(traceService::currentRequestIdOrDefault);
    }
}
EOF

echo ">>> Patch du pom.xml"

python3 - <<'PY'
from pathlib import Path
import re

pom_path = Path("pom.xml")
text = pom_path.read_text(encoding="utf-8")

# java.version -> 25
if "<java.version>" in text:
    text = re.sub(r"<java\.version>.*?</java\.version>", "<java.version>25</java.version>", text, flags=re.S)
else:
    text = re.sub(r"</properties>", "    <java.version>25</java.version>\n    </properties>", text, flags=re.S)

# ajouter validation si absent
if "spring-boot-starter-validation" not in text:
    validation_dep = """
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
"""
    text = re.sub(r"(</dependencies>)", validation_dep + r"\n    \1", text, count=1, flags=re.S)

pom_path.write_text(text, encoding="utf-8")
PY

echo ">>> Optionnel: ajout de logs SQL lisibles si application.properties existe"

APP_PROPS="$SRC_MAIN_RESOURCES/application.properties"
if [ -f "$APP_PROPS" ]; then
  grep -q '^spring.jpa.show-sql=' "$APP_PROPS" || echo 'spring.jpa.show-sql=true' >> "$APP_PROPS"
  grep -q '^spring.jpa.properties.hibernate.format_sql=' "$APP_PROPS" || echo 'spring.jpa.properties.hibernate.format_sql=true' >> "$APP_PROPS"
fi

echo ">>> Terminé"
echo ">>> Ensuite lance : mvn clean compile"
echo ">>> Puis : mvn spring-boot:run"