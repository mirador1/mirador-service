package com.mirador.customer;

import com.mirador.integration.TodoItem;
import com.mirador.customer.CreateCustomerRequest;
import com.mirador.customer.CustomerDto;
import com.mirador.customer.CustomerDtoV2;
import com.mirador.customer.EnrichedCustomerDto;
import com.mirador.messaging.CustomerEnrichReply;
import com.mirador.messaging.CustomerEnrichRequest;
import com.mirador.customer.AggregationService;
import com.mirador.integration.BioService;
import com.mirador.customer.CustomerService;
import com.mirador.customer.RecentCustomerBuffer;
import com.mirador.integration.TodoService;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
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
 * <p><b>Spring Boot 3 variant</b> — API versioning is handled via the {@code X-API-Version}
 * request header with manual dispatch, replacing Spring Framework 7's native
 * {@code @GetMapping(version = ...)} attribute which is not available in Spring 6.
 *
 * <p>Each endpoint is instrumented at two levels:
 * <ol>
 *   <li><b>Micrometer Timers/Counters</b> — low-latency histogram data exported to Prometheus.</li>
 *   <li><b>Micrometer Observations</b> — richer tracing spans exported via OpenTelemetry to Tempo.</li>
 * </ol>
 */
@RestController
@RequestMapping(CustomerController.PATH_CUSTOMERS)
public class CustomerController {

    private static final Logger log = LoggerFactory.getLogger(CustomerController.class);

    // Base path used in @RequestMapping, redirect headers, and observation span keys.
    // Mirrored from main/CustomerController.java so CustomerEnrichmentController +
    // CustomerDiagnosticsController can reference CustomerController.PATH_CUSTOMERS
    // when the SB3 overlay is active. Without this, compat-sb3-* fails compilation
    // with "cannot find symbol variable PATH_CUSTOMERS" — see svc 1.0.51 wave.
    static final String PATH_CUSTOMERS = "/customers";

    private final CustomerService service;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final AggregationService aggregationService;
    private final ObservationRegistry observationRegistry;
    private final ReplyingKafkaTemplate<String, CustomerEnrichRequest, CustomerEnrichReply> replyingKafkaTemplate;
    private final TodoService todoService;
    private final BioService bioService;
    private final String customerRequestTopic;
    private final long enrichTimeoutSeconds;

    private final Counter customerCreatedCounter;
    private final Timer customerCreateTimer;
    private final Timer customerFindAllTimer;
    private final Timer customerAggregateTimer;
    private final Timer customerEnrichTimer;

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

    // ─── API versioned list endpoint (SB3: manual header dispatch) ───────────

    /**
     * Returns a paginated list of customers, dispatching to v1 or v2 DTO shape
     * based on the {@code X-API-Version} request header.
     *
     * <p>This replaces the Spring Boot 4 / Spring Framework 7 native versioning
     * ({@code @GetMapping(version = "1.0")} / {@code version = "2.0+"}) with
     * manual header-based dispatch for Spring Boot 3 compatibility.
     *
     * <ul>
     *   <li>{@code X-API-Version: 1.0} (or absent) → v1 shape ({@link CustomerDto})</li>
     *   <li>{@code X-API-Version: 2.0} (or higher) → v2 shape ({@link CustomerDtoV2} with {@code createdAt})</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    @GetMapping
    public ResponseEntity<?> getAll(
            @RequestHeader(value = "X-API-Version", defaultValue = "1.0") String apiVersion,
            @PageableDefault(size = 20, sort = "id") Pageable pageable,
            @RequestParam(required = false) String search) {
        Pageable capped = capPageSize(pageable);
        if (apiVersion.startsWith("2") || apiVersion.compareTo("2") >= 0) {
            Page<CustomerDtoV2> page = Observation.createNotStarted("customer.find-all-v2", observationRegistry)
                    .lowCardinalityKeyValue("endpoint", "/customers")
                    .lowCardinalityKeyValue("version", "2")
                    .observe(() -> customerFindAllTimer.record(() ->
                            search != null ? service.searchV2(search, capped) : service.findAllV2(capped)));
            return withLinkHeaders(page);
        } else {
            Page<CustomerDto> page = Observation.createNotStarted("customer.find-all", observationRegistry)
                    .lowCardinalityKeyValue("endpoint", "/customers")
                    .observe(() -> customerFindAllTimer.record(() ->
                            search != null ? service.search(search, capped) : service.findAll(capped)));
            return withLinkHeaders(page, Map.of(
                    "Deprecation", "true",
                    "Sunset", "2027-01-01T00:00:00Z"));
        }
    }

    // ─── CRUD endpoints (identical to SB4 version) ─────────────────────────

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

    @GetMapping("/{id}")
    public CustomerDto getById(@PathVariable Long id) {
        return service.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public CustomerDto update(@PathVariable Long id, @Valid @RequestBody CreateCustomerRequest request) {
        return service.update(id, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    @GetMapping("/recent")
    public List<CustomerDto> getRecent() {
        return Observation.createNotStarted("customer.recent", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/recent")
                .observe(recentCustomerBuffer::getRecent);
    }

    @GetMapping("/summary")
    public Page<CustomerSummary> getSummary(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return service.findAllSummaries(pageable);
    }

    @GetMapping("/aggregate")
    public AggregationService.AggregatedResponse aggregate() {
        return Observation.createNotStarted("customer.aggregate", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/aggregate")
                .observe(() -> customerAggregateTimer.record(aggregationService::aggregate));
    }

    @GetMapping("/{id}/bio")
    public java.util.Map<String, String> generateBio(@PathVariable Long id) {
        CustomerDto customer = service.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
        return java.util.Map.of("bio", bioService.generateBio(customer));
    }

    @GetMapping("/{id}/todos")
    public List<TodoItem> getTodos(@PathVariable Long id) {
        service.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
        return todoService.getTodos(id);
    }

    @GetMapping("/{id}/enrich")
    public EnrichedCustomerDto enrich(@PathVariable Long id) {
        return Observation.createNotStarted("customer.enrich", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/{id}/enrich")
                .observe(() -> customerEnrichTimer.record(() -> {
                    CustomerDto customer = service.findById(id)
                            .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));

                    var record = new ProducerRecord<>(customerRequestTopic,
                            String.valueOf(id),
                            new CustomerEnrichRequest(customer.id(), customer.name(), customer.email()));

                    try {
                        CustomerEnrichReply reply = replyingKafkaTemplate
                                .sendAndReceive(record, java.time.Duration.ofSeconds(enrichTimeoutSeconds))
                                .get()
                                .value();
                        log.info("kafka_enrich_reply id={} displayName={}", id, reply.displayName());
                        return new EnrichedCustomerDto(reply.id(), reply.name(), reply.email(), reply.displayName());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted waiting for enrich reply", e);
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof KafkaReplyTimeoutException) {
                            throw new IllegalStateException("kafka-timeout",
                                    new java.util.concurrent.TimeoutException("Kafka reply timed out for id=" + id));
                        }
                        throw new IllegalStateException("Enrich failed for id=" + id, e.getCause());
                    }
                }));
    }

    // ─── Cursor-based pagination ────────────────────────────────────────────

    @GetMapping("/cursor")
    public CursorPage<CustomerDto> getAllCursor(
            @RequestParam(defaultValue = "0") Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        return service.findAllCursor(cursor, size);
    }

    // ─── Batch import ───────────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/batch")
    public BatchImportResult batchCreate(@Valid @RequestBody List<CreateCustomerRequest> requests) {
        return Observation.createNotStarted("customer.batch-import", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers/batch")
                .observe(() -> service.batchCreate(requests));
    }

    // ─── Slow query simulation ──────────────────────────────────────────────

    @GetMapping("/slow-query")
    public java.util.Map<String, String> slowQuery(@RequestParam(defaultValue = "2") double seconds) {
        double capped = Math.min(seconds, 10);
        service.simulateSlowQuery(capped);
        return java.util.Map.of("status", "completed", "duration", capped + "s");
    }

    // ─── CSV export ─────────────────────────────────────────────────────────

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

    private Pageable capPageSize(Pageable pageable) {
        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
    }

    private <T> ResponseEntity<Page<T>> withLinkHeaders(Page<T> page) {
        return withLinkHeaders(page, Map.of());
    }

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
