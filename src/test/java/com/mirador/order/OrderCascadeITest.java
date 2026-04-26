package com.mirador.order;

import com.mirador.AbstractIntegrationTest;
import com.mirador.customer.Customer;
import com.mirador.customer.CustomerRepository;
import com.mirador.product.Product;
import com.mirador.product.ProductRepository;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for invariant 6 of shared ADR-0059 :
 * <pre>
 *   DELETE order → CASCADE removes its OrderLines.
 *   DELETE order does NOT touch the referenced Product (FK RESTRICT side).
 *   DELETE product that's still referenced by an OrderLine FAILS (RESTRICT).
 * </pre>
 *
 * <p>These rules are enforced at the DB level via the FK constraints declared
 * in V8/V9 migrations :
 * <ul>
 *   <li>{@code order_line.order_id REFERENCES orders(id) ON DELETE CASCADE}</li>
 *   <li>{@code order_line.product_id REFERENCES product(id) ON DELETE RESTRICT}</li>
 * </ul>
 *
 * <p>The test uses {@code @SpringBootTest} + Testcontainers Postgres (via the
 * {@link AbstractIntegrationTest} base class) so the assertions exercise the
 * REAL FK rules — not just JPA cascade annotations.
 *
 * @see <a href="https://gitlab.com/mirador1/mirador-service-shared/-/blob/main/docs/adr/0059-customer-order-product-data-model.md">ADR-0059</a>
 */
class OrderCascadeITest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepo;

    @Autowired
    private OrderLineRepository lineRepo;

    @Autowired
    private ProductRepository productRepo;

    @Autowired
    private CustomerRepository customerRepo;

    private Long customerId;
    private Long productId;
    private Long orderId;
    private Long lineId;

    @BeforeEach
    void seed() {
        // Clean previous order/line/product. Don't deleteAll customers —
        // R__seed_demo_customers may be a repeatable seed other tests rely on.
        lineRepo.deleteAll();
        orderRepo.deleteAll();
        productRepo.deleteAll();

        // Create our own Customer for the FK — testcontainer Postgres
        // doesn't guarantee customer.id=1 exists (depends on R__seed timing
        // and test class ordering). Self-contained seed = robust to CI variance.
        Customer c = new Customer();
        c.setName("Cascade Tester " + System.nanoTime());
        c.setEmail("cascade-" + System.nanoTime() + "@example.com");
        customerId = customerRepo.save(c).getId();

        Product p = new Product();
        p.setName("Cascade-test-widget-" + System.nanoTime());
        p.setUnitPrice(new BigDecimal("9.99"));
        p.setStockQuantity(10);
        productId = productRepo.save(p).getId();

        Order o = new Order();
        o.setCustomerId(customerId);
        o.setStatus(OrderStatus.PENDING);
        o.setTotalAmount(new BigDecimal("9.99"));
        orderId = orderRepo.save(o).getId();

        OrderLine line = new OrderLine();
        line.setOrderId(orderId);
        line.setProductId(productId);
        line.setQuantity(1);
        line.setUnitPriceAtOrder(new BigDecimal("9.99"));
        line.setStatus(OrderLineStatus.PENDING);
        lineId = lineRepo.save(line).getId();
    }

    /**
     * Invariant 6 — happy cascade : deleting the parent order removes its
     * lines (DB-level CASCADE), but does NOT touch the referenced product.
     */
    @Test
    void deletingOrder_cascadesOrderLines_keepsProduct() {
        // Pre-conditions
        assertThat(orderRepo.findById(orderId)).as("order seeded").isPresent();
        assertThat(lineRepo.findById(lineId)).as("line seeded").isPresent();
        assertThat(productRepo.findById(productId)).as("product seeded").isPresent();

        // Act — deleteById commits in its own transaction, FK CASCADE fires at commit
        orderRepo.deleteById(orderId);

        // Post-conditions (fresh transaction reads from disk)
        assertThat(orderRepo.findById(orderId)).as("order removed").isEmpty();
        assertThat(lineRepo.findById(lineId)).as("line cascade-removed").isEmpty();
        assertThat(productRepo.findById(productId))
                .as("product NOT touched — FK RESTRICT side preserved")
                .isPresent();
    }

    /**
     * Invariant 6 — RESTRICT side : you can NOT delete a Product that is
     * still referenced by an OrderLine. Postgres raises a
     * {@code foreign_key_violation} which Spring wraps in a
     * {@link DataIntegrityViolationException}.
     *
     * <p>NOT marked {@code @Transactional} : we want each repo call to
     * commit/rollback in its own short transaction so we can OBSERVE the
     * failure of the delete + a fresh read afterwards. With a class-level
     * transaction, the abort would poison subsequent reads.
     */
    @Test
    void deletingProduct_referencedByLine_isRejected() {
        // Pre-condition : line exists referencing product
        assertThat(lineRepo.findById(lineId)).isPresent();

        // Act + assert : DELETE product must fail (RESTRICT)
        assertThatThrownBy(() -> productRepo.deleteById(productId))
                .isInstanceOfAny(DataIntegrityViolationException.class, PersistenceException.class);

        // Recovery : the product is still there (fresh transaction reads from disk)
        assertThat(productRepo.findById(productId))
                .as("product survives the failed delete attempt")
                .isPresent();
    }

    /**
     * Invariant 6 — multi-line cascade : deleting an order with N lines
     * removes ALL of them, not just one. Validates the FK CASCADE applies
     * symmetrically to a row set.
     */
    @Test
    void deletingOrder_cascadesAllLines() {
        // Add 2 more lines on the seeded order (each save in own transaction)
        for (int i = 0; i < 2; i++) {
            OrderLine extra = new OrderLine();
            extra.setOrderId(orderId);
            extra.setProductId(productId);
            extra.setQuantity(1);
            extra.setUnitPriceAtOrder(new BigDecimal("9.99"));
            extra.setStatus(OrderLineStatus.PENDING);
            lineRepo.save(extra);
        }

        List<OrderLine> linesBefore = lineRepo.findByOrderIdOrderByIdAsc(orderId);
        assertThat(linesBefore).as("3 lines seeded").hasSize(3);

        // Act
        orderRepo.deleteById(orderId);

        // All 3 lines gone
        assertThat(lineRepo.findByOrderIdOrderByIdAsc(orderId))
                .as("all lines cascade-removed")
                .isEmpty();

        // Product still here (RESTRICT not violated for any line — they all went via CASCADE)
        Optional<Product> p = productRepo.findById(productId);
        assertThat(p).as("product still here after multi-line cascade").isPresent();
    }
}
