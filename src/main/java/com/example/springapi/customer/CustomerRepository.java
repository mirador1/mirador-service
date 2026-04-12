package com.example.springapi.customer;

import com.example.springapi.customer.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Customer} entities.
 *
 * <p>Extending {@link JpaRepository} provides the full CRUD API ({@code save}, {@code findById},
 * {@code findAll}, {@code delete}, etc.) and pagination support ({@code findAll(Pageable)})
 * without any implementation code — Spring Data generates the proxy at startup.
 *
 * <p>Custom queries can be added here using:
 * <ul>
 *   <li>Derived method names: {@code findByEmail(String email)} → SQL is derived automatically.</li>
 *   <li>{@code @Query} annotation with JPQL or native SQL for complex queries.</li>
 *   <li>Specifications (JPA Criteria API) for dynamic predicates.</li>
 * </ul>
 *
 * <p>All queries issued through this repository are instrumented by
 * {@code datasource-micrometer-spring-boot} which wraps the DataSource in a proxy
 * and emits JDBC span observations, visible in Tempo traces as "DB" spans alongside the
 * HTTP request span.
 */
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    /**
     * Returns a page of {@link CustomerSummary} projections (id + name only).
     *
     * <p>Spring Data JPA generates {@code SELECT id, name FROM customer} — no full entity
     * graph is loaded. The method name {@code findAllProjectedBy} is a Spring Data convention
     * for "find all rows, projected to the return type".
     *
     * [Spring Data JPA — interface projection]
     */
    Page<CustomerSummary> findAllProjectedBy(Pageable pageable);
}
