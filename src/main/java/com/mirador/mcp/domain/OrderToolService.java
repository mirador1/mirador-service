package com.mirador.mcp.domain;

import com.mirador.order.Order;
import com.mirador.order.OrderDto;
import com.mirador.order.OrderRepository;
import com.mirador.order.OrderStatus;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP tool surface for the {@code order} aggregate.
 *
 * <p>Per ADR-0062 the {@code @Tool} annotations live on the service layer.
 * The existing {@code OrderController} talks to {@link OrderRepository}
 * directly — this MCP-facing service wraps the same repository while
 * keeping HTTP concerns out of the LLM contract (no {@code Pageable},
 * no {@code ResponseEntity}, no path variables).
 *
 * <p>All tools enforce a {@link #MAX_RESULTS} ceiling so a curious LLM
 * cannot exfiltrate the full table in one call.
 */
@Service
public class OrderToolService {

    /** Hard cap on {@code listRecentOrders} so list tools stay bounded. */
    public static final int MAX_RESULTS = 100;

    /**
     * Sentinel returned by {@link #getOrderById} when the row does not
     * exist. Using a structured map keeps the JSON shape stable for the
     * LLM (it sees {@code {"status":"not_found", "id":42}} instead of an
     * empty body, which it might mistake for "no answer").
     */
    private static final String STATUS_NOT_FOUND = "not_found";

    private final OrderRepository orders;

    public OrderToolService(OrderRepository orders) {
        this.orders = orders;
    }

    /**
     * Returns the most recent orders, optionally filtered by status.
     * Newest-first by {@code created_at}, capped at {@link #MAX_RESULTS}.
     *
     * @param limit  max rows ; 1..100. Values ≤ 0 fall back to 20.
     * @param status one of {@code PENDING / CONFIRMED / SHIPPED / CANCELLED} ;
     *               omit (null) to include all.
     * @return list of {@link OrderDto}, never null
     */
    @Tool(name = "list_recent_orders",
            description = "Lists orders newest-first, optionally filtered by status. Returns "
                    + "up to `limit` rows (max 100). Use this when the user asks 'what "
                    + "are the latest orders' or 'show me cancelled orders'.")
    public List<OrderDto> listRecentOrders(
            @ToolParam(description = "Max rows returned, 1..100 (default 20).")
            int limit,
            @ToolParam(required = false, description = "Status filter — PENDING, CONFIRMED, "
                    + "SHIPPED, or CANCELLED. Omit to include all statuses.")
            OrderStatus status
    ) {
        int effective = effectiveLimit(limit);
        var pageable = PageRequest.of(0, effective, Sort.by(Sort.Direction.DESC, "createdAt"));
        var orderPage = (status == null)
                ? orders.findAll(pageable)
                : orders.findByStatus(status, pageable);
        return orderPage.stream().map(OrderDto::from).toList();
    }

    /**
     * Returns the full order header by ID, or a structured "not_found"
     * sentinel — never throws.
     *
     * @param id primary key
     * @return either an {@link OrderDto} or a {@link Map} with {@code status=not_found, id=<id>}
     */
    @Tool(name = "get_order_by_id",
            description = "Returns the order header for a single ID. Returns a structured "
                    + "{status:'not_found', id:<id>} response when the row is absent — "
                    + "the tool never throws on a missing row.")
    public Object getOrderById(
            @ToolParam(description = "Numeric primary key of the order.")
            Long id
    ) {
        Optional<Order> found = orders.findById(id);
        return found.map(OrderDto::from)
                .map(dto -> (Object) dto)
                .orElseGet(() -> Map.of("status", STATUS_NOT_FOUND, "id", id));
    }

    /**
     * Creates a new empty order for the given customer (no lines yet).
     * Idempotency is enforced by the existing {@code IdempotencyFilter}
     * because the MCP HTTP transport reuses the same Spring filter chain.
     *
     * @param customerId FK on {@code customer.id} — must exist
     * @return the newly created {@link OrderDto}
     */
    @Tool(name = "create_order",
            description = "Creates a new empty order for a customer (no lines, status=PENDING, "
                    + "totalAmount=0). The customer must exist. Idempotent via the standard "
                    + "Idempotency-Key header inherited by the MCP endpoint.")
    @Transactional
    public OrderDto createOrder(
            @ToolParam(description = "Customer ID — must reference an existing customer row.")
            Long customerId
    ) {
        Order order = new Order();
        order.setCustomerId(customerId);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(BigDecimal.ZERO);
        return OrderDto.from(orders.save(order));
    }

    /**
     * Cancels an order by transitioning it to {@link OrderStatus#CANCELLED}.
     * The DB-level cascade on {@code order_line} (ON DELETE CASCADE) is
     * not triggered here — cancelling preserves the lines for audit.
     *
     * @param id primary key
     * @return either the updated {@link OrderDto} or a {@code not_found}
     *         sentinel when the row is absent
     */
    @Tool(name = "cancel_order",
            description = "Marks an order as CANCELLED. Lines are preserved for audit (no "
                    + "delete). Returns the updated DTO or {status:'not_found'} when the "
                    + "ID is unknown. Idempotent : cancelling an already-cancelled order "
                    + "is a no-op.")
    @Transactional
    public Object cancelOrder(
            @ToolParam(description = "Numeric primary key of the order.")
            Long id
    ) {
        return orders.findById(id)
                .map(order -> {
                    order.setStatus(OrderStatus.CANCELLED);
                    return (Object) OrderDto.from(orders.save(order));
                })
                .orElseGet(() -> Map.of("status", STATUS_NOT_FOUND, "id", id));
    }

    /**
     * Validates the user-supplied {@code limit} : non-positive falls back
     * to 20 (a reasonable default for "give me the latest orders"),
     * larger values are clipped to {@link #MAX_RESULTS}.
     */
    static int effectiveLimit(int limit) {
        if (limit <= 0) {
            return 20;
        }
        return Math.min(limit, MAX_RESULTS);
    }
}
