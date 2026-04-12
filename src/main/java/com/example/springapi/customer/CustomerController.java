package com.example.springapi.customer;

import com.example.springapi.integration.TodoItem;
import com.example.springapi.customer.CreateCustomerRequest;
import com.example.springapi.customer.CustomerDto;
import com.example.springapi.customer.CustomerDtoV2;
import com.example.springapi.customer.EnrichedCustomerDto;
import com.example.springapi.messaging.CustomerEnrichReply;
import com.example.springapi.messaging.CustomerEnrichRequest;
import com.example.springapi.customer.AggregationService;
import com.example.springapi.integration.BioService;
import com.example.springapi.customer.CustomerService;
import com.example.springapi.customer.RecentCustomerBuffer;
import com.example.springapi.integration.TodoService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    private final CustomerService service;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final AggregationService aggregationService;
    // ObservationRegistry is the Micrometer entry point for creating spans/traces
    private final ObservationRegistry observationRegistry;
    // Typed template for Pattern 2 (synchronous Kafka request-reply)
    private final ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate;
    private final TodoService todoService;
    private final BioService bioService;
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
                              ObservationRegistry observationRegistry,
                              ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate,
                              TodoService todoService,
                              BioService bioService,
                              @Value("${app.kafka.topics.customer-request}") String customerRequestTopic,
                              @Value("${app.kafka.enrich-timeout-seconds}") long enrichTimeoutSeconds,
                              MeterRegistry meterRegistry) {
        this.service = service;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.aggregationService = aggregationService;
        this.observationRegistry = observationRegistry;
        this.replyingKafkaTemplate = replyingKafkaTemplate;
        this.todoService = todoService;
        this.bioService = bioService;
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
    @GetMapping(version = "1.0")
    public Page<CustomerDto> getAll(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return Observation.createNotStarted("customer.find-all", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .observe(() -> customerFindAllTimer.record(() -> service.findAll(pageable)));
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
    @GetMapping(version = "2.0+")
    public Page<CustomerDtoV2> getAllV2(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return Observation.createNotStarted("customer.find-all-v2", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .lowCardinalityKeyValue("version", "2")
                .observe(() -> customerFindAllTimer.record(() -> service.findAllV2(pageable)));
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
    @PreAuthorize("hasRole('ADMIN')")
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

    /**
     * Returns the last 10 customers from the in-memory {@link RecentCustomerBuffer}.
     *
     * <p>No database query is issued — the buffer is populated on each {@code POST /customers}.
     * This pattern is useful to demonstrate the difference between a metric gauge (buffer size)
     * and a query-backed endpoint, and to illustrate the cost of DB round-trips vs. in-process reads.
     */
    @GetMapping("/recent")
    public List<CustomerDto> getRecent() {
        return Observation.createNotStarted("customer.recent", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/recent")
                .observe(recentCustomerBuffer::getRecent);
    }

    /**
     * Demonstrates structured concurrency using Java virtual threads.
     *
     * <p>Two independent data-loading tasks run in parallel on a virtual-thread executor.
     * The endpoint intentionally simulates latency (200 ms per task) to make the parallel
     * execution pattern visible in traces and in the {@code customer.aggregate.duration} metric.
     * See {@link AggregationService} for the implementation.
     */
    @GetMapping("/aggregate")
    public AggregationService.AggregatedResponse aggregate() {
        return Observation.createNotStarted("customer.aggregate", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/aggregate")
                .observe(() -> customerAggregateTimer.record(aggregationService::aggregate));
    }

    /**
     * Generates a professional bio for the customer using a local LLM (Ollama).
     *
     * <p>Spring AI {@code ChatClient} abstracts the LLM provider — swapping to OpenAI or
     * another model requires only a configuration change. The LLM call is synchronous and
     * blocking, so response time depends on model and hardware (~1–5 s on a local machine).
     */
    @GetMapping("/{id}/bio")
    public java.util.Map<String, String> generateBio(@PathVariable Long id) {
        CustomerDto customer = service.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
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
    @GetMapping("/{id}/todos")
    public List<TodoItem> getTodos(@PathVariable Long id) {
        service.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
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
    @GetMapping("/{id}/enrich")
    public EnrichedCustomerDto enrich(@PathVariable Long id) {
        return Observation.createNotStarted("customer.enrich", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/{id}/enrich")
                .observe(() -> customerEnrichTimer.record(() -> {
                    CustomerDto customer = service.findById(id)
                            .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));

                    // Build the Kafka record with the customer ID as the message key (partitioning)
                    var record = new ProducerRecord<>(customerRequestTopic,
                            String.valueOf(id),
                            new CustomerEnrichRequest(customer.id(), customer.name(), customer.email()));

                    try {
                        CustomerEnrichReply reply = replyingKafkaTemplate
                                .sendAndReceive(record, java.time.Duration.ofSeconds(enrichTimeoutSeconds))
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
}
