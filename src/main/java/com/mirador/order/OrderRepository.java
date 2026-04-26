package com.mirador.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link Order}.
 *
 * <p>Standard CRUD via {@link JpaRepository} + a couple of convenience
 * finders (by customer, by status). More complex queries belong in a
 * service layer with Specification or Querydsl.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** All orders placed by a customer, newest first. */
    List<Order> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** All orders in a given status. */
    List<Order> findByStatus(OrderStatus status);
}
