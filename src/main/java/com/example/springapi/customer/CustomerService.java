package com.example.springapi.customer;

import com.example.springapi.customer.CreateCustomerRequest;
import com.example.springapi.customer.CustomerDto;
import com.example.springapi.messaging.CustomerCreatedEvent;
import com.example.springapi.messaging.CustomerEventPublisher;
import com.example.springapi.customer.Customer;
import com.example.springapi.customer.CustomerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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
 * {@link com.example.springapi.config.KafkaConfig} with Jackson JSON serialization and
 * automatic {@code __TypeId__} headers so that consumers can deserialize to the correct type.
 */
@Service
public class CustomerService {

    private final CustomerRepository repository;
    private final RecentCustomerBuffer recentCustomerBuffer;
    // Resilient publisher: retries with exponential backoff instead of plain fire-and-forget
    private final CustomerEventPublisher eventPublisher;
    // Topic name injected from application.yml: app.kafka.topics.customer-created
    private final String customerCreatedTopic;

    public CustomerService(CustomerRepository repository,
                           RecentCustomerBuffer recentCustomerBuffer,
                           CustomerEventPublisher eventPublisher,
                           @Value("${app.kafka.topics.customer-created}") String customerCreatedTopic) {
        this.repository = repository;
        this.recentCustomerBuffer = recentCustomerBuffer;
        this.eventPublisher = eventPublisher;
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

    /** Returns the customer with the given ID, or {@code Optional.empty()} if not found. */
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
        CustomerDto dto = toDto(saved);
        recentCustomerBuffer.add(dto);

        // Pattern 1 — publish event with resilient retry (exponential backoff + jitter)
        // CustomerEventPublisher.publish blocks until broker ack, retrying on transient failures.
        // The HTTP response still returns after this call — retries complete within the request.
        eventPublisher.publish(customerCreatedTopic,
                String.valueOf(saved.getId()),
                new CustomerCreatedEvent(saved.getId(), saved.getName(), saved.getEmail()));

        return dto;
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
