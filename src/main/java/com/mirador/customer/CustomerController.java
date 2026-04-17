package com.mirador.customer;

import com.mirador.integration.TodoItem;
import com.mirador.observability.AuditEventDto;
import com.mirador.observability.AuditService;
import com.mirador.messaging.CustomerEnrichReply;
import com.mirador.messaging.CustomerEnrichRequest;
import com.mirador.integration.BioService;
import com.mirador.integration.TodoService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.requestreply.KafkaReplyTimeoutException;
import org.springframework.kafka.requestreply.ReplyingKafkaTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;

/**
 * REST controller exposing the customer management API.
 *
 * <p>Each endpoint is instrumented at two levels:
 * <ol>
 *   <li><b>Micrometer Timers/Counters</b> — low-latency histogram data exported to Prometheus,
 *       used in Grafana dashboards to visualise throughput and latency percentiles (p50/p95/p99).</li>
 *   <li><b>Micrometer Observations</b> — richer tracing spans exported via OpenTelemetry to Tempo,
 *       allowing distributed trace correlation across service boundaries.</li>
 * </ol>
 *
 * <p>Demonstrated patterns:
 * <ul>
 *   <li>{@code GET /customers} — paginated list with Spring Data {@code Pageable}.</li>
 *   <li>{@code POST /customers} — creates a customer, publishes an async Kafka event
 *       (fire-and-forget), increments the {@code customer.created.count} metric.</li>
 *   <li>{@code GET /customers/recent} — returns the last 10 customers from an in-memory buffer
 *       (no DB query), demonstrating in-process caching.</li>
 *   <li>{@code GET /customers/aggregate} — triggers two parallel virtual-thread tasks to show
 *       Java 21+ structured concurrency.</li>
 *   <li>{@code GET /customers/{id}/bio} — delegates to Spring AI (Ollama) to generate
 *       a professional bio using an LLM.</li>
 *   <li>{@code GET /customers/{id}/todos} — calls JSONPlaceholder via HTTP Interface with
 *       Resilience4j circuit breaker + retry.</li>
 *   <li>{@code GET /customers/{id}/enrich} — synchronous Kafka request-reply: sends a request
 *       to {@code customer.request} and blocks until the reply arrives on {@code customer.reply}
 *       (or times out after {@code app.kafka.enrich-timeout-seconds}).</li>
 * </ul>
 *
 * <p>All metrics (timers, counters) are registered eagerly in the constructor via the
 * fluent Micrometer builder API so that they appear in {@code /actuator/metrics} immediately,
 * even before the first request hits the endpoint.
 */
@Tag(name = "Customers", description = "Customer CRUD, search, export, real-time stream, and advanced patterns (Kafka enrich, virtual threads, cursor pagination, LLM bio generation)")
@RestController
@RequestMapping(CustomerController.PATH_CUSTOMERS)
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    // Sonar java:S1192 — centralise repeated string literals.
    // Base path used in @RequestMapping, redirect headers, and observation span keys.
    static final String PATH_CUSTOMERS = "/customers";
    // "endpoint" key used in every observation span for low-cardinality routing context.
    private static final String KEY_ENDPOINT  = "endpoint";
    // Error message prefix — appears in multiple orElseThrow() exception messages.
    private static final String ERR_NOT_FOUND = "Customer not found: ";

    private final CustomerService service;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final AggregationService aggregationService;
    private final AuditService auditService;  // used for GET /{id}/audit — customer-scoped audit trail
    // ObservationRegistry is the Micrometer entry point for creating spans/traces
    private final ObservationRegistry observationRegistry;
    // Typed template for Pattern 2 (synchronous Kafka request-reply)
    private final ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate;
    private final TodoService todoService;
    private final BioService bioService;
    private final SseEmitterRegistry sseEmitterRegistry;
    // Injected from application.yml: app.kafka.topics.customer-request
    private final String customerRequestTopic;
    // Injected from application.yml: app.kafka.enrich-timeout-seconds
    private final long enrichTimeoutSeconds;

    // ─── Micrometer metrics (registered at startup, not per-request) ──────────
    private final Counter customerCreatedCounter;   // total customer creations
    private final Timer customerCreateTimer;        // latency histogram for POST /customers
    private final Timer customerFindAllTimer;       // latency histogram for GET /customers
    private final Timer customerAggregateTimer;     // latency histogram for GET /customers/aggregate
    private final Timer customerEnrichTimer;        // latency histogram for GET /customers/{id}/enrich

    /**
     * Constructor injection (preferred over field injection for testability and immutability).
     * Metrics are built here so they exist from the very first scrape by Prometheus,
     * ensuring zero gaps in Grafana graphs even before any request has been processed.
     * {@code publishPercentileHistogram()} enables server-side histogram buckets so that
     * Prometheus can compute accurate percentiles without client-side approximation.
     */
    public CustomerController(CustomerService service,
                              RecentCustomerBuffer recentCustomerBuffer,
                              AggregationService aggregationService,
                              AuditService auditService,
                              ObservationRegistry observationRegistry,
                              ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate,
                              TodoService todoService,
                              BioService bioService,
                              SseEmitterRegistry sseEmitterRegistry,
                              @Value("${app.kafka.topics.customer-request}") String customerRequestTopic,
                              @Value("${app.kafka.enrich-timeout-seconds}") long enrichTimeoutSeconds,
                              MeterRegistry meterRegistry) {
        this.service = service;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.aggregationService = aggregationService;
        this.auditService = auditService;
        this.observationRegistry = observationRegistry;
        this.replyingKafkaTemplate = replyingKafkaTemplate;
        this.todoService = todoService;
        this.bioService = bioService;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.customerRequestTopic = customerRequestTopic;
        this.enrichTimeoutSeconds = enrichTimeoutSeconds;
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
        this.customerEnrichTimer = Timer.builder("customer.enrich.duration")
                .description("Duration of Kafka request-reply enrich endpoint")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    /**
     * [API v1] Returns a paginated list of customers (id, name, email).
     *
     * <p>Matched when the client sends {@code X-API-Version: 1.0} or omits the header
     * (default version). {@code @PageableDefault} sets page size and sort when the caller
     * omits the {@code page}/{@code size}/{@code sort} query parameters.
     *
     * <p>[Spring 7 / Spring Boot 4] The {@code version} attribute on {@code @GetMapping}
     * is a Spring Framework 7 feature — it narrows the handler mapping to requests whose
     * resolved API version matches "1.0". Routing is performed by
     * {@link org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping}
     * using the {@link org.springframework.web.accept.ApiVersionStrategy} configured in
     * {@code spring.mvc.apiversion.*} in {@code application.yml}.
     */
    @Operation(summary = "List customers (v1)",
            description = "Paginated list of customers (id, name, email). Default version — also returned when `X-API-Version` header is absent. "
                    + "Supports optional full-text search on name and email. "
                    + "Adds RFC 8288 `Link` headers (next, prev, first, last) and `Deprecation`/`Sunset` headers.")
    @ApiResponse(responseCode = "200", description = "Page of customers",
            headers = {
                    @Header(name = "Link", description = "RFC 8288 pagination links", schema = @Schema(type = "string")),
                    @Header(name = "Deprecation", description = "Marks v1 as deprecated", schema = @Schema(type = "string")),
                    @Header(name = "Sunset", description = "Date after which v1 will be removed", schema = @Schema(type = "string"))
            })
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token", content = @Content)
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded (100 req/min per IP)", content = @Content)
    @GetMapping(version = "1.0")
    public ResponseEntity<Page<CustomerDto>> getAll(
            @PageableDefault(size = 20, sort = "id") Pageable pageable,
            @Parameter(description = "Optional search term — filters on name and email (case-insensitive, partial match)")
            @RequestParam(required = false) String search) {
        Pageable capped = capPageSize(pageable);
        Page<CustomerDto> page = Observation.createNotStarted("customer.find-all", observationRegistry)
                .lowCardinalityKeyValue(KEY_ENDPOINT, PATH_CUSTOMERS)
                .observe(() -> customerFindAllTimer.record(() ->
                        search != null ? service.search(search, capped) : service.findAll(capped)));
        return withLinkHeaders(page, Map.of(
                "Deprecation", "true",
                "Sunset", "2027-01-01T00:00:00Z"));
    }

    /**
     * [API v2] Returns a paginated list of customers including {@code createdAt} timestamp.
     *
     * <p>Matched when the client sends {@code X-API-Version: 2.0}.
     * Returns {@link CustomerDtoV2} which adds the {@code createdAt} field
     * sourced from the {@code created_at TIMESTAMPTZ} column (Flyway V3 migration).
     *
     * <p>The {@code version = "2.0+"} baseline syntax means "2.0 and any later version",
     * so future v3 clients will continue to receive this response unless a v3 handler
     * is defined. This is a key advantage of Spring 7's native versioning: baseline
     * semantics allow forward-compatible endpoints without code duplication.
     *
     * <p>[Spring 7 / Spring Boot 4] Demonstrates the API versioning feature introduced in
     * Spring Framework 7.0 ({@code @RequestMapping#version()}).
     */
    @Operation(summary = "List customers (v2)",
            description = "Same as v1 but includes `createdAt` (ISO-8601 UTC timestamp). "
                    + "Requires `X-API-Version: 2.0` header. "
                    + "Uses Spring Framework 7 native versioning — `version = \"2.0+\"` means this handler serves 2.0 and any future version until a higher one is declared.")
    @ApiResponse(responseCode = "200", description = "Page of customers including createdAt")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token", content = @Content)
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    @GetMapping(version = "2.0+")
    public ResponseEntity<Page<CustomerDtoV2>> getAllV2(
            @PageableDefault(size = 20, sort = "id") Pageable pageable,
            @Parameter(description = "Optional search term — filters on name and email")
            @RequestParam(required = false) String search) {
        Pageable capped = capPageSize(pageable);
        Page<CustomerDtoV2> page = Observation.createNotStarted("customer.find-all-v2", observationRegistry)
                .lowCardinalityKeyValue(KEY_ENDPOINT, PATH_CUSTOMERS)
                .lowCardinalityKeyValue("version", "2")
                .observe(() -> customerFindAllTimer.record(() ->
                        search != null ? service.searchV2(search, capped) : service.findAllV2(capped)));
        return withLinkHeaders(page);
    }

    /**
     * Creates a new customer.
     *
     * <p>{@code @Valid} triggers Bean Validation on the request body; any violation throws
     * {@code MethodArgumentNotValidException} which is caught in {@link ApiExceptionHandler}
     * and returned as an RFC 7807 Problem Detail (HTTP 400).
     * After a successful save the service also publishes a {@code CustomerCreatedEvent} on
     * Kafka (Pattern 1 — fire-and-forget) and adds the customer to the in-memory buffer.
     * Both the Micrometer counter and timer are incremented here.
     */
    // [Spring Security] — method-level check complements the URL rule in SecurityConfig.
    // When a Keycloak token carries ROLE_ADMIN in realm_access.roles, this passes.
    @Operation(summary = "Create a customer",
            description = "Creates a new customer, fires a Kafka `CustomerCreatedEvent` (fire-and-forget), "
                    + "adds the customer to the in-memory recent buffer, and increments the `customer.created.count` Micrometer counter. "
                    + "Requires `ROLE_ADMIN`. Supports idempotency via the `Idempotency-Key` header — repeating the same request returns the cached response.")
    @ApiResponse(responseCode = "200", description = "Customer created")
    @ApiResponse(responseCode = "400", description = "Validation failed (blank name, invalid email, etc.)", content = @Content)
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Authenticated but lacks ROLE_USER or ROLE_ADMIN", content = @Content)
    @ApiResponse(responseCode = "409", description = "Email already in use", content = @Content)
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded", content = @Content)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")   // ROLE_ADMIN or ROLE_USER — ROLE_READER is denied
    @PostMapping
    public CustomerDto create(@Valid @RequestBody CreateCustomerRequest request) {
        return Observation.createNotStarted("customer.create", observationRegistry)
                .lowCardinalityKeyValue(KEY_ENDPOINT, PATH_CUSTOMERS)
                .observe(() -> customerCreateTimer.record(() -> {
                    CustomerDto result = service.create(request);
                    customerCreatedCounter.increment();
                    return result;
                }));
    }

    /**
     * Returns a single customer by ID.
     *
     * <p>Returns HTTP 404 if the customer does not exist.
     */
    @Operation(summary = "Get a customer by ID")
    @ApiResponse(responseCode = "200", description = "Customer found")
    @ApiResponse(responseCode = "404", description = "No customer with this ID", content = @Content)
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token", content = @Content)
    @GetMapping("/{id}")
    public CustomerDto getById(
            @Parameter(description = "Customer ID", example = "42")
            @PathVariable Long id) {
        return service.findById(id)
                .orElseThrow(() -> new NoSuchElementException(ERR_NOT_FOUND + id));
    }

    /**
     * Updates an existing customer's name and email.
     *
     * <p>Returns HTTP 404 if the customer does not exist.
     */
    @Operation(summary = "Update a customer", description = "Replaces the name and email of an existing customer. Requires ROLE_USER or ROLE_ADMIN.")
    @ApiResponse(responseCode = "200", description = "Customer updated")
    @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content)
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Lacks ROLE_USER or ROLE_ADMIN", content = @Content)
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")   // ROLE_ADMIN or ROLE_USER — ROLE_READER is denied
    @PutMapping("/{id}")
    public CustomerDto update(
            @Parameter(description = "Customer ID", example = "42") @PathVariable Long id,
            @Valid @RequestBody CreateCustomerRequest request) {
        return service.update(id, request);
    }

    /**
     * Partially updates a customer's name and/or email.
     *
     * <p>Only provided (non-null) fields are applied — unlike PUT which replaces the entire resource.
     * Useful for updating a single field without knowing the current values of the other fields.
     */
    @Operation(summary = "Partially update a customer",
            description = "Applies only the provided fields. Omit fields you do not want to change. Requires ROLE_USER or ROLE_ADMIN.")
    @ApiResponse(responseCode = "200", description = "Customer updated")
    @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content)
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Lacks ROLE_USER or ROLE_ADMIN", content = @Content)
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")   // same roles as PUT — ROLE_READER is denied
    @org.springframework.web.bind.annotation.PatchMapping("/{id}")
    public CustomerDto patch(
            @Parameter(description = "Customer ID", example = "42") @PathVariable Long id,
            @Valid @RequestBody PatchCustomerRequest request) {
        return service.patch(id, request);
    }

    /**
     * Deletes a customer by ID.
     *
     * <p>Returns HTTP 204 (No Content) on success, HTTP 404 if the customer does not exist.
     */
    @Operation(summary = "Delete a customer", description = "Permanently deletes the customer. Requires ROLE_ADMIN.")
    @ApiResponse(responseCode = "204", description = "Deleted successfully", content = @Content)
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Lacks ROLE_ADMIN", content = @Content)
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "Customer ID", example = "42") @PathVariable Long id) {
        service.delete(id);
    }

    /**
     * Returns the audit trail for a specific customer (create, update, delete, patch events).
     *
     * <p>Useful for compliance and debugging — shows who performed which action and when.
     * Requires authentication (any role can view audit trail for any customer).
     */
    @Operation(summary = "Get audit trail for a customer",
            description = "Returns all audit events (CREATE, UPDATE, DELETE, PATCH) for the given customer ID.")
    @ApiResponse(responseCode = "200", description = "List of audit events")
    @GetMapping("/{id}/audit")
    public List<AuditEventDto> getAudit(
            @Parameter(description = "Customer ID") @PathVariable Long id) {
        return auditService.findByCustomerId(id);
    }

    /**
     * Returns the last 10 customers from the in-memory {@link RecentCustomerBuffer}.
     *
     * <p>No database query is issued — the buffer is populated on each {@code POST /customers}.
     * This pattern is useful to demonstrate the difference between a metric gauge (buffer size)
     * and a query-backed endpoint, and to illustrate the cost of DB round-trips vs. in-process reads.
     */
    @Operation(summary = "Recent customers (in-memory buffer)",
            description = "Returns the last 10 customers from the in-memory `RecentCustomerBuffer`. "
                    + "No DB query — populated on each `POST /customers`. Demonstrates in-process caching vs DB round-trips.")
    @ApiResponse(responseCode = "200", description = "Up to 10 most recently created customers")
    @GetMapping("/recent")
    public List<CustomerDto> getRecent() {
        return Observation.createNotStarted("customer.recent", observationRegistry)
                .lowCardinalityKeyValue(KEY_ENDPOINT, "/customers/recent")
                .observe(recentCustomerBuffer::getRecent);
    }

    /**
     * Returns a lightweight summary page (id + name only) using a Spring Data interface projection.
     *
     * <p>Spring Data JPA generates {@code SELECT id, name FROM customer} instead of
     * {@code SELECT *}. This avoids transferring unused columns (email, created_at) from
     * the database to the application for read-heavy list views.
     *
     * <p>The {@link CustomerSummary} interface projection is resolved by Spring Data JPA
     * without any DTO mapper — each getter is backed directly by the query result column.
     *
     * [Spring Data JPA — interface projection]
     */
    @GetMapping("/summary")
    public Page<CustomerSummary> getSummary(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return service.findAllSummaries(pageable);
    }

    /**
     * Demonstrates structured concurrency using Java virtual threads.
     *
     * <p>Two independent data-loading tasks run in parallel on a virtual-thread executor.
     * The endpoint intentionally simulates latency (200 ms per task) to make the parallel
     * execution pattern visible in traces and in the {@code customer.aggregate.duration} metric.
     * See {@link AggregationService} for the implementation.
     */
    @Operation(summary = "Aggregate stats via virtual threads",
            description = "Runs two data-loading tasks in parallel using Java virtual threads (Project Loom). "
                    + "Each task takes ~200 ms — both run concurrently so the total is ~200 ms, not ~400 ms. "
                    + "Visible in traces (two parallel child spans) and in the `customer.aggregate.duration` metric.")
    @ApiResponse(responseCode = "200", description = "Aggregated result with count, average name length, and virtual-thread timing")
    @GetMapping("/aggregate")
    public AggregationService.AggregatedResponse aggregate() {
        return Observation.createNotStarted("customer.aggregate", observationRegistry)
                .lowCardinalityKeyValue(KEY_ENDPOINT, "/customers/aggregate")
                .observe(() -> customerAggregateTimer.record(aggregationService::aggregate));
    }

    /**
     * Generates a professional bio for the customer using a local LLM (Ollama).
     *
     * <p>Spring AI {@code ChatClient} abstracts the LLM provider — swapping to OpenAI or
     * another model requires only a configuration change. The LLM call is synchronous and
     * blocking, so response time depends on model and hardware (~1–5 s on a local machine).
     */
    @Operation(summary = "Generate AI bio (Ollama LLM)",
            description = "Calls the local Ollama LLM (llama3.2) via Spring AI to generate a professional bio for the customer. "
                    + "Protected by a Resilience4j **circuit breaker** + **retry** — returns 503 when the circuit is open. "
                    + "Response time: 1–10 s depending on model and hardware.")
    @ApiResponse(responseCode = "200", description = "Bio generated — `{\"bio\": \"...\"}` ")
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @ApiResponse(responseCode = "503", description = "Ollama unavailable or circuit breaker open", content = @Content)
    @GetMapping("/{id}/bio")
    public java.util.Map<String, String> generateBio(
            @Parameter(description = "Customer ID", example = "3") @PathVariable Long id) {
        CustomerDto customer = service.findById(id)
                .orElseThrow(() -> new NoSuchElementException(ERR_NOT_FOUND + id));
        return java.util.Map.of("bio", bioService.generateBio(customer));
    }

    /**
     * Fetches todos from the external JSONPlaceholder API for a given customer.
     *
     * <p>The call goes through {@link TodoService} which is decorated with:
     * <ul>
     *   <li>{@code @CircuitBreaker} — opens after configurable failure rate, preventing
     *       cascading failures if JSONPlaceholder is down.</li>
     *   <li>{@code @Retry} — retries up to N times with backoff before giving up.</li>
     *   <li>Fallback — returns an empty list when the circuit is open or all retries fail,
     *       ensuring graceful degradation rather than an error response.</li>
     * </ul>
     */
    @Operation(summary = "Get todos from JSONPlaceholder (external API)",
            description = "Fetches todos for the customer from the external JSONPlaceholder API (https://jsonplaceholder.typicode.com). "
                    + "Decorated with Resilience4j **circuit breaker** + **retry** + fallback (empty list). "
                    + "Demonstrates graceful degradation — never returns 5xx even if the external API is down.")
    @ApiResponse(responseCode = "200", description = "List of todos (may be empty if circuit is open or API is down)")
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @GetMapping("/{id}/todos")
    public List<TodoItem> getTodos(
            @Parameter(description = "Customer ID", example = "3") @PathVariable Long id) {
        service.findById(id)
                .orElseThrow(() -> new NoSuchElementException(ERR_NOT_FOUND + id));
        return todoService.getTodos(id);
    }

    /**
     * Enriches a customer with a computed {@code displayName} using synchronous Kafka request-reply
     * (Pattern 2).
     *
     * <p>Flow:
     * <ol>
     *   <li>A {@link CustomerEnrichRequest} is published to {@code customer.request} via
     *       {@code ReplyingKafkaTemplate}. A {@code KafkaReplyHeaders.REPLY_TOPIC} header is
     *       automatically added by the template, instructing the consumer where to send the reply.</li>
     *   <li>The call blocks on {@code .get()} until the reply arrives on {@code customer.reply},
     *       or until {@code enrichTimeoutSeconds} elapses.</li>
     *   <li>On timeout, {@code KafkaReplyTimeoutException} is wrapped in an
     *       {@code IllegalStateException} whose cause is a {@code TimeoutException}, which is then
     *       caught in {@link ApiExceptionHandler} and returned as HTTP 504.</li>
     * </ol>
     *
     * <p>This pattern is demonstrated to show that Kafka can be used for synchronous
     * interactions, not just asynchronous fire-and-forget messaging.
     */
    @Operation(summary = "Enrich customer via Kafka request-reply",
            description = "Sends a `CustomerEnrichRequest` to the `customer.request` Kafka topic and **blocks** until the consumer "
                    + "replies on `customer.reply` with a computed `displayName`. "
                    + "Demonstrates synchronous Kafka request-reply (not just fire-and-forget). "
                    + "Returns `504 Gateway Timeout` if no reply arrives within the configured timeout.")
    @ApiResponse(responseCode = "200", description = "Enriched customer with displayName")
    @ApiResponse(responseCode = "404", description = "Customer not found", content = @Content)
    @ApiResponse(responseCode = "504", description = "Kafka consumer did not reply within the timeout", content = @Content)
    @GetMapping("/{id}/enrich")
    public EnrichedCustomerDto enrich(
            @Parameter(description = "Customer ID", example = "3") @PathVariable Long id) {
        return Observation.createNotStarted("customer.enrich", observationRegistry)
                .lowCardinalityKeyValue(KEY_ENDPOINT, "/customers/{id}/enrich")
                .observe(() -> customerEnrichTimer.record(() -> {
                    CustomerDto customer = service.findById(id)
                            .orElseThrow(() -> new NoSuchElementException(ERR_NOT_FOUND + id));

                    // Build the Kafka record with the customer ID as the message key (partitioning)
                    var producerRecord = new ProducerRecord<>(customerRequestTopic,
                            String.valueOf(id),
                            new CustomerEnrichRequest(customer.id(), customer.name(), customer.email()));

                    try {
                        CustomerEnrichReply reply = replyingKafkaTemplate
                                .sendAndReceive(producerRecord, java.time.Duration.ofSeconds(enrichTimeoutSeconds))
                                .get()  // blocks the current thread until reply or timeout
                                .value();
                        log.info("kafka_enrich_reply id={} displayName={}", id, reply.displayName());
                        return new EnrichedCustomerDto(reply.id(), reply.name(), reply.email(), reply.displayName());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted waiting for enrich reply", e);
                    } catch (ExecutionException e) {
                        // KafkaReplyTimeoutException extends KafkaException, NOT java.util.concurrent.TimeoutException,
                        // so we must check the cause explicitly and re-wrap for the exception handler to detect it
                        if (e.getCause() instanceof KafkaReplyTimeoutException) {
                            throw new IllegalStateException("kafka-timeout",
                                    new java.util.concurrent.TimeoutException("Kafka reply timed out for id=" + id));
                        }
                        throw new IllegalStateException("Enrich failed for id=" + id, e.getCause());
                    }
                }));
    }

    // ─── SSE stream ─────────────────────────────────────────────────────────

    /**
     * Server-Sent Events stream that pushes newly created customers in real time.
     *
     * <p>Each new customer creation triggers a {@code customer} event containing
     * the {@link CustomerDto} JSON payload. A {@code ping} event is sent every 30 s
     * by {@link SseEmitterRegistry} to keep the connection alive.
     *
     * <p>Requires authentication (any authenticated user).
     */
    @Operation(summary = "Server-Sent Events stream",
            description = "Opens an SSE connection that pushes `customer` events (JSON) whenever a new customer is created. "
                    + "A `ping` event is sent every 30 s to keep the connection alive. "
                    + "**No authentication required** — `EventSource` cannot send custom headers, so this endpoint is `permitAll`.")
    @ApiResponse(responseCode = "200", description = "SSE stream — content-type: text/event-stream")
    @SecurityRequirements
    @GetMapping(value = "/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return sseEmitterRegistry.register();
    }

    // ─── Cursor-based pagination ────────────────────────────────────────────

    /**
     * Returns a cursor-based page of customers.
     *
     * <p>Unlike offset-based pagination ({@code ?page=5&size=20}), cursor pagination uses
     * the last element's ID as a bookmark: {@code ?cursor=42&size=20}. The DB query uses
     * {@code WHERE id > 42 ORDER BY id LIMIT 21} (fetches size+1 to detect {@code hasNext}).
     *
     * <p>Performance advantage: offset-based pagination scans and skips rows, becoming slower
     * as the page number increases. Cursor pagination always does an index seek.
     */
    @Operation(summary = "Cursor-based pagination",
            description = "Returns customers using `WHERE id > cursor ORDER BY id LIMIT size+1`. "
                    + "Faster than offset-based pagination for large datasets — always an index seek, never a full scan. "
                    + "Pass the last returned `id` as `cursor` in the next request.")
    @ApiResponse(responseCode = "200", description = "Page with `items`, `nextCursor`, and `hasNext`")
    @GetMapping("/cursor")
    public CursorPage<CustomerDto> getAllCursor(
            @RequestParam(defaultValue = "0") Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        return service.findAllCursor(cursor, size);
    }

    // ─── Batch import ───────────────────────────────────────────────────────

    /**
     * Creates multiple customers in a single request.
     *
     * <p>Each entry is validated and persisted individually — a failure on one row does not
     * abort the entire batch. Duplicate emails are detected and reported as errors.
     * Successfully created customers trigger Kafka events and WebSocket notifications.
     */
    @Operation(summary = "Batch import customers",
            description = "Creates multiple customers in one request. Each entry is validated individually — a failure on one row does not abort the batch. "
                    + "Returns a summary with `created`, `failed`, and `errors` lists. Requires ROLE_USER or ROLE_ADMIN.")
    @ApiResponse(responseCode = "200", description = "Batch result with per-row status")
    @ApiResponse(responseCode = "401", description = "Missing or invalid JWT token", content = @Content)
    @ApiResponse(responseCode = "403", description = "Lacks ROLE_USER or ROLE_ADMIN", content = @Content)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")   // ROLE_ADMIN or ROLE_USER — ROLE_READER is denied
    @PostMapping("/batch")
    public BatchImportResult batchCreate(@Valid @RequestBody List<CreateCustomerRequest> requests) {
        return Observation.createNotStarted("customer.batch-import", observationRegistry)
                .lowCardinalityKeyValue(KEY_ENDPOINT, "/customers/batch")
                .observe(() -> service.batchCreate(requests));
    }

    // ─── CSV export ─────────────────────────────────────────────────────────

    /**
     * Streams all customers as a CSV file.
     *
     * <p>Uses {@link StreamingResponseBody} so the response is written directly to the
     * output stream without buffering the entire result set in memory. This is observable
     * in Tempo as a single long-running span whose duration grows with the dataset size.
     */
    // ─── Slow query simulation ────────────────────────────────────────────

    /**
     * Simulates a slow database query using PostgreSQL {@code pg_sleep()}.
     *
     * <p>Useful for observability demos: the long-running DB span is clearly visible
     * in Grafana Tempo traces, and the latency spike appears in Grafana dashboards.
     *
     * @param seconds duration of the simulated slow query (capped at 10s)
     */
    @Operation(summary = "Simulate a slow database query",
            description = "Runs `SELECT pg_sleep(N)` to inject artificial latency. "
                    + "The resulting DB span is visible in distributed traces (Grafana Tempo). "
                    + "Max 10 seconds.")
    @ApiResponse(responseCode = "200", description = "Query completed — returns `{status, duration}`")
    @GetMapping("/slow-query")
    public java.util.Map<String, String> slowQuery(
            @Parameter(description = "Sleep duration in seconds (capped at 10)", example = "2")
            @RequestParam(defaultValue = "2") double seconds) {
        double capped = Math.min(seconds, 10);
        service.simulateSlowQuery(capped);
        return java.util.Map.of("status", "completed", "duration", capped + "s");
    }

    // ─── CSV export ─────────────────────────────────────────────────────────

    @Operation(summary = "Export all customers as CSV",
            description = "Streams all customers directly to the response output stream using `StreamingResponseBody` — "
                    + "avoids loading the entire result set into memory. "
                    + "Returns `Content-Disposition: attachment; filename=customers.csv`.")
    @ApiResponse(responseCode = "200", description = "CSV file stream",
            content = @Content(mediaType = "text/csv"))
    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> exportCsv() {
        StreamingResponseBody body = outputStream -> {
            var writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            writer.println("id,name,email,created_at");
            for (Customer c : service.findAllForExport()) {
                writer.printf("%d,\"%s\",\"%s\",%s%n",
                        c.getId(),
                        c.getName().replace("\"", "\"\""),
                        c.getEmail().replace("\"", "\"\""),
                        c.getCreatedAt());
            }
            writer.flush();
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=customers.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private static final int MAX_PAGE_SIZE = 100;

    /** Caps the page size to prevent unbounded queries (e.g., ?size=999999). */
    private Pageable capPageSize(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }

    /** Adds RFC 8288 Link headers (next, prev, first, last) to paginated responses. */
    private <T> ResponseEntity<Page<T>> withLinkHeaders(Page<T> page) {
        return withLinkHeaders(page, Map.of());
    }

    /** Adds Link headers + optional extra headers (e.g., Deprecation, Sunset). */
    private <T> ResponseEntity<Page<T>> withLinkHeaders(Page<T> page, Map<String, String> extraHeaders) {
        var links = new java.util.StringJoiner(", ");
        String base = "/customers?size=" + page.getSize();
        if (page.hasNext()) {
            links.add("<%s&page=%d>; rel=\"next\"".formatted(base, page.getNumber() + 1));
        }
        if (page.hasPrevious()) {
            links.add("<%s&page=%d>; rel=\"prev\"".formatted(base, page.getNumber() - 1));
        }
        links.add("<%s&page=0>; rel=\"first\"".formatted(base));
        links.add("<%s&page=%d>; rel=\"last\"".formatted(base, page.getTotalPages() - 1));

        var builder = ResponseEntity.ok()
                .header("Link", links.toString());
        extraHeaders.forEach(builder::header);
        return builder.body(page);
    }
}
