package com.mirador.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link OrderLine}.
 *
 * <p>Standard CRUD via {@link JpaRepository} + per-order convenience finder.
 */
@Repository
public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {

    /** All lines belonging to a given order, in insertion order. */
    List<OrderLine> findByOrderIdOrderByIdAsc(Long orderId);
}
