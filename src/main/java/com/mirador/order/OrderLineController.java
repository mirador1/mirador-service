package com.mirador.order;

import com.mirador.product.Product;
import com.mirador.product.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * REST controller for {@link OrderLine} — nested under
 * {@code /orders/{orderId}/lines}.
 *
 * <p>Foundation MR : list, add, delete a line. Recomputes Order.totalAmount
 * after every add/delete in a transaction (denormalised total stays
 * consistent with the line-level data).
 *
 * <p>Out of scope (follow-up) : line-status transitions (PENDING → SHIPPED
 * → REFUNDED), partial refund flows, optimistic locking on the order
 * during line mutation, Kafka events on line lifecycle.
 */
@Tag(name = "OrderLines", description = "Order lines (nested under /orders/{orderId}/lines)")
@RestController
@RequestMapping("/orders/{orderId}/lines")
public class OrderLineController {

    private final OrderLineRepository lineRepo;
    private final OrderRepository orderRepo;
    private final ProductRepository productRepo;

    public OrderLineController(OrderLineRepository lineRepo, OrderRepository orderRepo, ProductRepository productRepo) {
        this.lineRepo = lineRepo;
        this.orderRepo = orderRepo;
        this.productRepo = productRepo;
    }

    @Operation(summary = "List all lines of an order")
    @GetMapping
    public List<OrderLineDto> list(@PathVariable Long orderId) {
        return lineRepo.findByOrderIdOrderByIdAsc(orderId).stream()
                .map(OrderLineDto::from)
                .toList();
    }

    @Operation(summary = "Add a line to an order (snapshots Product.unitPrice + recomputes Order.total)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrderLineDto add(@PathVariable Long orderId, @Valid @RequestBody CreateOrderLineRequest req) {
        // Verify order + product exist
        Order order = orderRepo.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order " + orderId + " not found"));
        Product product = productRepo.findById(req.productId())
                .orElseThrow(() -> new IllegalArgumentException("Product " + req.productId() + " not found"));

        OrderLine line = new OrderLine();
        line.setOrderId(orderId);
        line.setProductId(req.productId());
        line.setQuantity(req.quantity());
        line.setUnitPriceAtOrder(product.getUnitPrice()); // SNAPSHOT
        line.setStatus(OrderLineStatus.PENDING);
        OrderLine saved = lineRepo.save(line);

        // Recompute Order.totalAmount
        recomputeOrderTotal(order);

        return OrderLineDto.from(saved);
    }

    @Operation(summary = "Delete a line from an order (recomputes Order.total)")
    @DeleteMapping("/{lineId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long orderId, @PathVariable Long lineId) {
        Optional<OrderLine> existing = lineRepo.findById(lineId);
        if (existing.isEmpty() || !existing.get().getOrderId().equals(orderId)) {
            return ResponseEntity.notFound().build();
        }
        lineRepo.deleteById(lineId);

        // Recompute Order.totalAmount
        orderRepo.findById(orderId).ifPresent(this::recomputeOrderTotal);

        return ResponseEntity.noContent().build();
    }

    /**
     * Update an {@link OrderLine}'s status — state-machine validated per
     * <a href="https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0063-order-line-refund-state-machine.md">shared ADR-0063</a>.
     *
     * <p>Forward-only graph : {@code PENDING → SHIPPED → REFUNDED}. The
     * skip {@code PENDING → REFUNDED} is rejected by design — a refund
     * must follow a shipment for audit traceability.
     *
     * <p>Refunding does NOT change the line's monetary value : the
     * snapshot {@code unitPriceAtOrder} stays as-is so historical
     * orders remain auditable. Money flow (issuing the refund through
     * a payment processor) is OUT OF SCOPE per ADR-0063 §"Consequences :
     * Negative" — the state transition is the trigger event ; an
     * orchestrator listens to it and handles the financial side.
     *
     * <p>Same wire shape as the Python sibling so the UI doesn't branch.
     *
     * @return 200 + updated DTO on valid transition,
     *         404 if order or line missing,
     *         409 ProblemDetail with currentStatus + targetStatus + reason
     *           on forbidden transition,
     *         422 if status is unknown.
     */
    @Operation(summary = "Update an order line's status (state-machine validated, ADR-0063)",
            description = "Body : {\"status\": \"PENDING|SHIPPED|REFUNDED\", \"reason\": \"…optional…\"}. "
                    + "Valid transitions : PENDING → SHIPPED → REFUNDED. PENDING → REFUNDED rejected (audit "
                    + "requirement). Self-transitions allowed (idempotency). Refund does NOT mutate the price "
                    + "snapshot — money flow handled separately.")
    @PatchMapping("/{lineId}/status")
    @Transactional
    public ResponseEntity<?> updateLineStatus(
            @PathVariable Long orderId,
            @PathVariable Long lineId,
            @Valid @RequestBody UpdateOrderLineStatusRequest req) {
        Optional<OrderLine> existing = lineRepo.findById(lineId);
        if (existing.isEmpty() || !existing.get().getOrderId().equals(orderId)) {
            return ResponseEntity.notFound().build();
        }
        OrderLine line = existing.get();
        OrderLineStatus current = line.getStatus();
        OrderLineStatus target = req.status();
        if (!current.canTransitionTo(target)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of(
                    "type", "urn:problem:invalid-line-status-transition",
                    "title", "Invalid order line status transition",
                    "status", 409,
                    "detail", "Cannot transition line from " + current + " to " + target,
                    "currentStatus", current.name(),
                    "targetStatus", target.name(),
                    "reason", req.reason() == null ? "" : req.reason()
            ));
        }
        line.setStatus(target);
        OrderLine saved = lineRepo.save(line);
        // Audit log : the structured log line is what AuditService picks up
        // (per the existing JWT correlation pattern) ; the reason carries
        // forward into the audit_event row. The total recompute is NOT
        // triggered : refunding does NOT change line.quantity *
        // unit_price_at_order per ADR-0063 §"Refund refunds the snapshot".
        org.slf4j.LoggerFactory.getLogger(OrderLineController.class)
                .info("order_line_status_changed orderId={} lineId={} from={} to={} reason={}",
                        orderId, lineId, current, target,
                        req.reason() == null ? "" : req.reason());
        return ResponseEntity.ok(OrderLineDto.from(saved));
    }

    /**
     * Recompute and persist {@code Order.totalAmount} as the sum of
     * {@code line.quantity × line.unitPriceAtOrder} for all lines of the order.
     */
    private void recomputeOrderTotal(Order order) {
        BigDecimal total = lineRepo.findByOrderIdOrderByIdAsc(order.getId()).stream()
                .map(l -> l.getUnitPriceAtOrder().multiply(BigDecimal.valueOf(l.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalAmount(total);
        orderRepo.save(order);
    }
}
