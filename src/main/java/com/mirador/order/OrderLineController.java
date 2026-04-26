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
