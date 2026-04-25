package com.mirador.customer;

import com.mirador.customer.CreateCustomerRequest;
import com.mirador.customer.CustomerDto;
import com.mirador.customer.CustomerDtoV2;
import com.mirador.customer.AggregationService;
import com.mirador.customer.CustomerService;
import com.mirador.customer.RecentCustomerBuffer;
import com.mirador.observability.AuditEventDto;
import com.mirador.observability.AuditService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

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
    // ADR-0060 + svc 1.0.55 wave 6 alignment : SB3 overlay constructor now
    // mirrors main's 6-param signature. Fields removed (kafka template, todo
    // service, bio service, customerRequestTopic, enrichTimeoutSeconds,
    // customerEnrichTimer) were moved to CustomerEnrichmentController in
    // main 2026-04-22 — those endpoints (/{id}/enrich, /{id}/bio,
    // /{id}/todos) and the slow-query / export endpoints (moved to
    // CustomerDiagnosticsController) are now served by their respective
    // controllers in SB3 mode too. AuditService field added to mirror
    // main + power the /{id}/audit endpoint.
    private final AuditService auditService;
    private final ObservationRegistry observationRegistry;

    private final Counter customerCreatedCounter;
    private final Timer customerCreateTimer;
    private final Timer customerFindAllTimer;
    private final Timer customerAggregateTimer;

    public CustomerController(CustomerService service,
                              RecentCustomerBuffer recentCustomerBuffer,
                              AggregationService aggregationService,
                              AuditService auditService,
                              ObservationRegistry observationRegistry,
                              MeterRegistry meterRegistry) {
        this.service = service;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.aggregationService = aggregationService;
        this.auditService = auditService;
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
        // customerEnrichTimer removed — moved to CustomerEnrichmentController
        // along with the /{id}/enrich endpoint (Phase B-7-7 follow-through).
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
    /**
     * Spring endpoint dispatcher : reads the X-API-Version header (manual
     * dispatch since SB3's Spring 6 doesn't have @GetMapping(version=))
     * and delegates to the appropriate v1/v2 helper. The helpers below are
     * test-callable directly so the unit-test signature matches main's
     * (which uses native version dispatch via two separate @GetMapping
     * methods named getAll + getAllV2).
     */
    @GetMapping
    public ResponseEntity<?> getAllDispatcher(
            @RequestHeader(value = "X-API-Version", defaultValue = "1.0") String apiVersion,
            @PageableDefault(size = 20, sort = "id") Pageable pageable,
            @RequestParam(required = false) String search) {
        if (apiVersion.startsWith("2") || apiVersion.compareTo("2") >= 0) {
            return getAllV2(pageable, search);
        }
        return getAll(pageable, search);
    }

    /**
     * V1 list endpoint (no Spring annotation — invoked by getAllDispatcher OR
     * from unit tests directly). Mirrors main's getAll signature so SB3
     * mode passes the same CustomerControllerTest as default mode.
     */
    public ResponseEntity<Page<CustomerDto>> getAll(Pageable pageable, String search) {
        Pageable capped = capPageSize(pageable);
        Page<CustomerDto> page = Observation.createNotStarted("customer.find-all", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .observe(() -> customerFindAllTimer.record(() ->
                        search != null ? service.search(search, capped) : service.findAll(capped)));
        return withLinkHeaders(page, Map.of(
                "Deprecation", "true",
                "Sunset", "2027-01-01T00:00:00Z"));
    }

    /**
     * V2 list endpoint (no Spring annotation — invoked by getAllDispatcher OR
     * from unit tests directly). Mirrors main's getAllV2 signature.
     */
    public ResponseEntity<Page<CustomerDtoV2>> getAllV2(Pageable pageable, String search) {
        Pageable capped = capPageSize(pageable);
        Page<CustomerDtoV2> page = Observation.createNotStarted("customer.find-all-v2", observationRegistry)
                .lowCardinalityKeyValue("endpoint", "/customers")
                .lowCardinalityKeyValue("version", "2")
                .observe(() -> customerFindAllTimer.record(() ->
                        search != null ? service.searchV2(search, capped) : service.findAllV2(capped)));
        return withLinkHeaders(page);
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

    /**
     * Partial update via PATCH. Added in svc 1.0.55 wave 6 to mirror main —
     * the test (CustomerControllerTest) expects controller.patch(id, req) to
     * exist with this exact signature.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @PatchMapping("/{id}")
    public CustomerDto patch(@PathVariable Long id, @Valid @RequestBody PatchCustomerRequest request) {
        return service.patch(id, request);
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

    /**
     * Audit trail endpoint — added in svc 1.0.55 wave 6 to mirror main's structure.
     * Returns all audit events (CREATE/UPDATE/DELETE/PATCH) for a customer.
     */
    @GetMapping("/{id}/audit")
    public List<AuditEventDto> getAudit(@PathVariable Long id) {
        return auditService.findByCustomerId(id);
    }

    // /{id}/bio + /{id}/todos + /{id}/enrich endpoints removed from this
    // SB3 overlay — moved to CustomerEnrichmentController in main 2026-04-22
    // (Phase B-7-7). Spring auto-wires CustomerEnrichmentController in SB3
    // mode too (it uses standard Spring MVC + kafka deps available in SB3).

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

    // /slow-query + /export endpoints removed from this SB3 overlay —
    // moved to CustomerDiagnosticsController in main 2026-04-22 (Phase B-7-7).
    // Spring auto-wires CustomerDiagnosticsController in SB3 mode too (it
    // uses standard Spring MVC + SseEmitter / StreamingResponseBody available
    // in SB3).

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
