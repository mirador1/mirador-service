package com.mirador.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * REST controller for order management.
 *
 * <p>Foundation MR (2026-04-26) : list + get + create (empty) + delete.
 * OrderLine endpoints + status transitions + customer-scoped queries
 * deferred to follow-up MRs.
 */
@Tag(name = "Orders", description = "Order management : list, get, create (empty), delete")
@RestController
@RequestMapping(OrderController.PATH_ORDERS)
public class OrderController {

    static final String PATH_ORDERS = "/orders";

    private final OrderRepository repo;

    public OrderController(OrderRepository repo) {
        this.repo = repo;
    }

    @Operation(summary = "List orders (paginated)")
    @GetMapping
    public Page<OrderDto> list(@PageableDefault(size = 20) Pageable pageable) {
        return repo.findAll(pageable).map(OrderDto::from);
    }

    @Operation(summary = "Get order by ID")
    @GetMapping("/{id}")
    public ResponseEntity<OrderDto> get(@PathVariable Long id) {
        return repo.findById(id)
                .map(OrderDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Operation(summary = "Create a new (empty) order — lines added via /orders/{id}/lines (follow-up MR)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDto create(@Valid @RequestBody CreateOrderRequest req) {
        Order o = new Order();
        o.setCustomerId(req.customerId());
        o.setStatus(OrderStatus.PENDING);
        o.setTotalAmount(BigDecimal.ZERO);
        Order saved = repo.save(o);
        return OrderDto.from(saved);
    }

    /**
     * Update the status of an existing order. Status transitions follow
     * the state machine declared on {@link OrderStatus#canTransitionTo} :
     *
     * <ul>
     *   <li>PENDING → CONFIRMED, CANCELLED (or self).</li>
     *   <li>CONFIRMED → SHIPPED, CANCELLED (or self).</li>
     *   <li>SHIPPED, CANCELLED → terminal (only self).</li>
     * </ul>
     *
     * <p>Invalid transitions return 409 with a ProblemDetail body
     * surfacing the source / target / allowed-set so the UI can render
     * a meaningful error. 404 if the order doesn't exist.
     *
     * <p>This endpoint mirrors the Python sibling exactly so the UI's
     * order-edit screen works identically against either backend.
     */
    @Operation(summary = "Update order status — PUT /orders/{id}/status",
            description = "Body : {\"status\": \"PENDING|CONFIRMED|SHIPPED|CANCELLED\"}. "
                    + "Returns 200 + updated order on success, 404 if missing, "
                    + "409 if the requested transition violates the state machine, "
                    + "422 if the body is malformed.")
    @PutMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody UpdateOrderStatusRequest req) {
        var order = repo.findById(id).orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        OrderStatus current = order.getStatus();
        OrderStatus target = req.status();
        if (!current.canTransitionTo(target)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "type", "urn:problem:invalid-status-transition",
                    "title", "Invalid order status transition",
                    "status", 409,
                    "detail", "Cannot transition from " + current + " to " + target,
                    "currentStatus", current.name(),
                    "targetStatus", target.name()
            ));
        }
        order.setStatus(target);
        Order saved = repo.save(order);
        return ResponseEntity.ok(OrderDto.from(saved));
    }

    @Operation(summary = "Delete an order (CASCADE deletes its lines per V9 ON DELETE CASCADE)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        repo.deleteById(id);
    }
}
