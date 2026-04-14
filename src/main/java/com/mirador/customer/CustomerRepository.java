package com.mirador.customer;

import com.mirador.customer.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

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

    /**
     * Cursor-based pagination: returns customers whose ID is strictly greater than {@code cursor},
     * ordered by ID ascending. The query uses an index seek ({@code WHERE id > ?}) instead of
     * {@code OFFSET}, making it efficient on large datasets.
     */
    List<Customer> findByIdGreaterThanOrderByIdAsc(Long cursor, Pageable pageable);

    /**
     * Checks if a customer with the given email already exists.
     * Used by batch import to skip duplicates.
     */
    boolean existsByEmail(String email);

    /**
     * Full-text search on name and email (case-insensitive LIKE).
     * Used by {@code GET /customers?search=alice}.
     */
    @Query("SELECT c FROM Customer c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(c.email) LIKE LOWER(CONCAT('%', :search, '%'))")
    Page<Customer> search(String search, Pageable pageable);

    /**
     * Intentionally slow query for observability demos.
     * Calls PostgreSQL {@code pg_sleep()} to simulate a long-running query.
     */
    @Query(value = "SELECT pg_sleep(:seconds)", nativeQuery = true)
    void simulateSlowQuery(double seconds);
}
