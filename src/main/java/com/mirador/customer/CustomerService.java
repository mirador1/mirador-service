package com.mirador.customer;

import com.mirador.messaging.CustomerCreatedEvent;
import com.mirador.messaging.CustomerEventPublisher;
import com.mirador.observability.AuditService;
import io.micrometer.observation.annotation.Observed;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Application service for customer management.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>CRUD operations delegated to {@link CustomerRepository} (Spring Data JPA).</li>
 *   <li>After each successful creation, the new customer is added to the
 *       {@link RecentCustomerBuffer} (in-memory cache for {@code GET /customers/recent}).</li>
 *   <li>An async {@link CustomerCreatedEvent} is published to Kafka (Pattern 1 — fire-and-forget):
 *       the HTTP response is returned immediately without waiting for the consumer to process
 *       the event. This keeps the endpoint latency low and decouples downstream processing.</li>
 * </ul>
 *
 * <p>The {@code KafkaTemplate<String, Object>} uses the shared producer factory configured in
 * {@link com.mirador.config.KafkaConfig} with Jackson JSON serialization and
 * automatic {@code __TypeId__} headers so that consumers can deserialize to the correct type.
 *
 * <p>{@code @Observed} at class level wraps every public method in a Micrometer Observation span
 * named {@code customer.service}, visible in Grafana Tempo as child spans of the HTTP server span.
 */
@Observed(name = "customer.service")  // wraps every public method in a Micrometer Observation span
@Service
public class CustomerService {

    // Sonar java:S1192 — MDC key and log fragment appear 6+ times each.
    private static final String MDC_CUSTOMER_ID = "customerId";
    private static final String LOG_NAME_FRAG   = " name=";

    private final CustomerRepository repository;
    private final RecentCustomerBuffer recentCustomerBuffer;
    private final CustomerEventPublisher eventPublisher;
    private final SimpMessagingTemplate websocket;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final AuditService auditService;
    private final String customerCreatedTopic;

    public CustomerService(CustomerRepository repository,
                           RecentCustomerBuffer recentCustomerBuffer,
                           CustomerEventPublisher eventPublisher,
                           SimpMessagingTemplate websocket,
                           SseEmitterRegistry sseEmitterRegistry,
                           AuditService auditService,
                           @Value("${app.kafka.topics.customer-created}") String customerCreatedTopic) {
        this.repository = repository;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.eventPublisher = eventPublisher;
        this.websocket = websocket;
        this.sseEmitterRegistry = sseEmitterRegistry;
        this.auditService = auditService;
        this.customerCreatedTopic = customerCreatedTopic;
    }

    /** Returns a page of customers (v1 shape — no createdAt). */
    public Page<CustomerDto> findAll(Pageable pageable) {
        return repository.findAll(pageable).map(this::toDto);
    }

    /** Returns a page of customers (v2 shape — includes createdAt). */
    public Page<CustomerDtoV2> findAllV2(Pageable pageable) {
        return repository.findAll(pageable).map(this::toDtoV2);
    }

    /**
     * Returns a page of lightweight projections (id + name only — no SELECT *).
     * Demonstrates Spring Data JPA interface projections for query optimisation.
     * [Spring Data JPA — interface projection]
     */
    public Page<CustomerSummary> findAllSummaries(Pageable pageable) {
        return repository.findAllProjectedBy(pageable);
    }

    /**
     * Returns the customer with the given ID, or {@code Optional.empty()} if not found.
     *
     * <p>Results are cached in the Caffeine {@code customer-by-id} cache (max 1000 entries,
     * 5-minute TTL). The cache is evicted on {@link #update} and {@link #delete} to ensure
     * reads reflect the latest data. {@link #create} does not populate the cache because the
     * entity ID is only known after save.
     */
    @Cacheable(value = "customer-by-id", key = "#id")  // cache hit skips DB round-trip
    public Optional<CustomerDto> findById(Long id) {
        return repository.findById(id).map(this::toDto);
    }

    /**
     * Persists a new customer and triggers two side effects:
     * <ol>
     *   <li>Adds the customer to the in-memory recent-customers buffer.</li>
     *   <li>Publishes a {@link CustomerCreatedEvent} to Kafka using the customer ID as the
     *       message key (guarantees ordering for the same customer on the same partition).
     *       The HTTP response is returned <em>before</em> the consumer has processed the event.</li>
     * </ol>
     */
    public CustomerDto create(CreateCustomerRequest request) {
        Customer customer = new Customer();
        customer.setName(request.name());
        customer.setEmail(request.email());

        Customer saved = repository.save(customer);
        // Enrich MDC so all subsequent log lines in this thread carry the new customer ID
        org.slf4j.MDC.put(MDC_CUSTOMER_ID, String.valueOf(saved.getId()));
        CustomerDto dto = toDto(saved);
        recentCustomerBuffer.add(dto);

        // Pattern 1 — publish event with resilient retry (exponential backoff + jitter)
        eventPublisher.publish(customerCreatedTopic,
                String.valueOf(saved.getId()),
                new CustomerCreatedEvent(saved.getId(), saved.getName(), saved.getEmail()));

        // WebSocket — push real-time notification to all connected clients
        websocket.convertAndSend("/topic/customers", dto);

        // SSE — push to all active Server-Sent Events subscribers
        sseEmitterRegistry.send("customer", dto);

        auditService.log(currentUser(), "CUSTOMER_CREATED",
                "id=" + saved.getId() + LOG_NAME_FRAG + saved.getName(), null);
        org.slf4j.MDC.remove(MDC_CUSTOMER_ID);  // clean up MDC to avoid leaking across requests
        return dto;
    }

    /**
     * Updates an existing customer's name and email.
     * The Caffeine cache entry for this customer is evicted so the next {@link #findById}
     * fetches fresh data from the database.
     *
     * @throws NoSuchElementException if the customer does not exist
     */
    @CacheEvict(value = "customer-by-id", key = "#id")  // evict stale cache entry on write
    public CustomerDto update(Long id, CreateCustomerRequest request) {
        Customer customer = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
        customer.setName(request.name());
        customer.setEmail(request.email());
        Customer saved = repository.save(customer);
        // Enrich MDC so the audit log line carries the customer ID for log correlation
        org.slf4j.MDC.put(MDC_CUSTOMER_ID, String.valueOf(saved.getId()));
        auditService.log(currentUser(), "CUSTOMER_UPDATED",
                "id=" + id + LOG_NAME_FRAG + saved.getName(), null);
        org.slf4j.MDC.remove(MDC_CUSTOMER_ID);  // clean up MDC to avoid leaking across requests
        return toDto(saved);
    }

    /**
     * Deletes a customer by ID.
     * The Caffeine cache entry for this customer is evicted so subsequent reads do not return
     * stale data for a deleted entity.
     *
     * @throws NoSuchElementException if the customer does not exist
     */
    @CacheEvict(value = "customer-by-id", key = "#id")  // evict stale cache entry on delete
    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("Customer not found: " + id);
        }
        // Enrich MDC before deleteById so the audit log line carries the customer ID
        org.slf4j.MDC.put(MDC_CUSTOMER_ID, String.valueOf(id));
        repository.deleteById(id);
        auditService.log(currentUser(), "CUSTOMER_DELETED", "id=" + id, null);
        org.slf4j.MDC.remove(MDC_CUSTOMER_ID);  // clean up MDC to avoid leaking across requests
    }

    /**
     * Partially updates a customer — only non-null, non-blank fields in the request are applied.
     * The cache entry for this customer is evicted so the next {@link #findById} fetches fresh data.
     *
     * @throws NoSuchElementException if the customer does not exist
     */
    @CacheEvict(value = "customer-by-id", key = "#id")  // evict stale cache entry on partial write
    public CustomerDto patch(Long id, PatchCustomerRequest request) {
        Customer customer = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Customer not found: " + id));
        // Apply only fields that are explicitly provided (non-null and non-blank)
        if (request.name() != null && !request.name().isBlank()) {
            customer.setName(request.name());
        }
        if (request.email() != null && !request.email().isBlank()) {
            customer.setEmail(request.email());
        }
        Customer saved = repository.save(customer);
        auditService.log(currentUser(), "CUSTOMER_PATCHED",
                "id=" + id + LOG_NAME_FRAG + saved.getName(), null);
        return toDto(saved);
    }

    /** Returns the authenticated user's name from the security context, or "anonymous". */
    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "anonymous";
    }

    /**
     * Cursor-based pagination: returns customers whose ID is greater than {@code cursor}.
     * Uses {@code WHERE id > :cursor ORDER BY id ASC LIMIT :size} — efficient index seek.
     */
    public CursorPage<CustomerDto> findAllCursor(Long cursor, int size) {
        List<Customer> customers = repository.findByIdGreaterThanOrderByIdAsc(
                cursor, PageRequest.of(0, size + 1));
        boolean hasNext = customers.size() > size;
        List<Customer> page = hasNext ? customers.subList(0, size) : customers;
        Long nextCursor = hasNext ? page.getLast().getId() : null;
        return new CursorPage<>(page.stream().map(this::toDto).toList(), nextCursor, hasNext, size);
    }

    /**
     * Batch import: creates multiple customers in one request.
     * Skips duplicates (by email) and collects errors without aborting the whole batch.
     */
    public BatchImportResult batchCreate(List<CreateCustomerRequest> requests) {
        List<CustomerDto> created = new ArrayList<>();
        List<BatchImportResult.BatchError> errors = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            CreateCustomerRequest req = requests.get(i);
            try {
                if (repository.existsByEmail(req.email())) {
                    errors.add(new BatchImportResult.BatchError(i, req.name(), "Email already exists"));
                    continue;
                }
                created.add(create(req));
            } catch (Exception e) {
                // Broad catch is intentional: batch items are independent — a constraint
                // violation, validation error, or DB failure on one row must not abort the rest.
                errors.add(new BatchImportResult.BatchError(i, req.name(), e.getMessage()));
            }
        }

        return new BatchImportResult(requests.size(), created.size(), errors.size(), created, errors);
    }

    /** Returns all customers as a flat list (for CSV export). */
    public List<Customer> findAllForExport() {
        return repository.findAll();
    }

    /** Search customers by name or email (case-insensitive). */
    public Page<CustomerDto> search(String query, Pageable pageable) {
        return repository.search(query, pageable).map(this::toDto);
    }

    /** Search customers by name or email — v2 shape with createdAt. */
    public Page<CustomerDtoV2> searchV2(String query, Pageable pageable) {
        return repository.search(query, pageable).map(this::toDtoV2);
    }

    /** Simulates a slow database query for observability demos. */
    public void simulateSlowQuery(double seconds) {
        repository.simulateSlowQuery(seconds);
    }

    /** Maps a JPA entity to a v1 DTO (id, name, email). */
    private CustomerDto toDto(Customer customer) {
        return new CustomerDto(
                customer.getId(),
                customer.getName(),
                customer.getEmail()
        );
    }

    /** Maps a JPA entity to a v2 DTO (id, name, email, createdAt). */
    private CustomerDtoV2 toDtoV2(Customer customer) {
        return new CustomerDtoV2(
                customer.getId(),
                customer.getName(),
                customer.getEmail(),
                customer.getCreatedAt()
        );
    }
}
