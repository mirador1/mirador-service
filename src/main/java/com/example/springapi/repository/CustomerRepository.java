package com.example.springapi.repository;

import com.example.springapi.model.Customer;
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
}
