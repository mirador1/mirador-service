package com.mirador.mcp.domain;

import com.mirador.customer.Customer;
import com.mirador.customer.CustomerRepository;
import com.mirador.mcp.dto.Customer360Dto;
import com.mirador.order.Order;
import com.mirador.order.OrderRepository;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * MCP tool surface for customer-360 aggregates.
 *
 * <p>Why a dedicated service rather than adding {@code @Tool} on
 * {@link com.mirador.customer.CustomerService} ?
 * <ul>
 *   <li>{@code CustomerService} owns Kafka publishing, WebSocket fan-out,
 *       SSE registry, audit writes, observation spans. Mixing
 *       {@code @Tool}-annotated read methods with that machinery couples
 *       LLM-facing concerns to internal infrastructure (a non-trivial
 *       coupling reviewers shouldn't have to mentally untangle).</li>
 *   <li>The {@code get_customer_360} aggregate joins customers with the
 *       {@code orders} table — that aggregation logic doesn't belong in
 *       {@code CustomerService} either ; it's a read-side concern that
 *       happens to live behind an MCP tool.</li>
 * </ul>
 * Splitting keeps the tool surface clean and the domain service focused.
 */
@Service
public class CustomerToolService {

    /** Sentinel for "no row" — same shape as {@code OrderToolService}. */
    private static final String STATUS_NOT_FOUND = "not_found";

    private final CustomerRepository customers;
    private final OrderRepository orders;

    public CustomerToolService(CustomerRepository customers, OrderRepository orders) {
        this.customers = customers;
        this.orders = orders;
    }

    /**
     * Returns a customer's header plus three aggregates over their orders :
     * count, total revenue, last-order timestamp. Reads happen in a single
     * transaction so the aggregates are coherent even if a concurrent
     * write is in flight.
     *
     * @param id customer primary key
     * @return either a {@link Customer360Dto} or {@code {status:'not_found', id}}
     */
    @Tool(name = "get_customer_360",
            description = "Returns a customer's header (id, name, email) plus aggregates over "
                    + "their orders : count, total revenue (sum of orders.total_amount), "
                    + "and last-order timestamp. Use this when the user asks 'tell me "
                    + "about customer 42' or 'what's customer 42's lifetime value'.")
    @Transactional(readOnly = true)
    public Object getCustomer360(
            @ToolParam(description = "Customer ID — primary key on the customer table.")
            Long id
    ) {
        return customers.findById(id)
                .map(c -> (Object) buildSnapshot(c, ordersFor(c.getId())))
                .orElseGet(() -> Map.of("status", STATUS_NOT_FOUND, "id", id));
    }

    /**
     * Loads the full order list for a customer — needed because the
     * aggregate cannot be computed by a derived-method query alone.
     * Bounded by repository pagination semantics ({@code List<Order>}
     * fits in heap for the demo dataset ; production would replace this
     * with a JPQL aggregation query).
     */
    private List<Order> ordersFor(Long customerId) {
        return orders.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }

    /** Builds the snapshot from a fetched customer + their orders. */
    private Customer360Dto buildSnapshot(Customer customer, List<Order> orderList) {
        long count = orderList.size();
        BigDecimal totalRevenue = orderList.stream()
                .map(Order::getTotalAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant lastOrderAt = orderList.isEmpty()
                ? null
                : orderList.get(0).getCreatedAt(); // already sorted DESC by createdAt
        return new Customer360Dto(
                customer.getId(),
                customer.getName(),
                customer.getEmail(),
                count,
                totalRevenue,
                lastOrderAt
        );
    }
}
