package com.mirador.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * Paginated variant for the MCP {@code list_recent_orders} tool —
     * lets callers cap the row count without loading the whole status
     * partition. The {@link Pageable} carries the sort (created_at DESC)
     * so the same query supports newest-first + limit semantics.
     */
    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * All orders that contain at least one {@link OrderLine} referencing
     * the given product, paginated.
     *
     * <p>Replaces the UI-side fan-out previously implemented as
     * "list 50 recent orders + filter client-side" in
     * {@code ProductDetailComponent#findConsumerOrders}. Server-side
     * filtering keeps the query bounded regardless of the order volume.
     *
     * <p>Uses {@code DISTINCT} since a single order may contain multiple
     * lines for the same product (rare but possible — e.g. a re-order
     * scenario where the user added the same product twice with
     * different quantities). Without {@code DISTINCT} that order would
     * appear twice in the page.
     *
     * <p>JPQL navigates through {@link OrderLine} via the FK
     * {@code productId} (we don't model a {@code @ManyToOne Product}
     * relationship on OrderLine — see the entity comment — so the
     * JPQL goes through the raw FK).
     */
    @Query("""
            SELECT DISTINCT o
            FROM Order o, OrderLine ol
            WHERE ol.orderId = o.id
              AND ol.productId = :productId
            """)
    Page<Order> findByProductId(@Param("productId") Long productId, Pageable pageable);
}
