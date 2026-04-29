package org.iris.order;

import org.iris.AbstractIntegrationTest;
import org.iris.customer.Customer;
import org.iris.customer.CustomerRepository;
import org.iris.product.Product;
import org.iris.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP roundtrip integration tests for {@code /orders} + {@code /orders/{id}/lines}.
 *
 * <p>Complements {@link OrderCascadeITest} (DB-level FK CASCADE/RESTRICT
 * rules) by exercising the HTTP→Controller→Service→Repository→DB chain
 * with real status codes, JSON bodies, and the OrderStatus state machine.
 *
 * <p>Coverage gaps closed (vs the existing
 * {@link OrderControllerTest} which uses {@code @WebMvcTest}) :
 * <ul>
 *   <li>End-to-end totalAmount recompute when adding/removing lines</li>
 *   <li>Real Bean Validation 400 paths (missing customerId, negative qty)</li>
 *   <li>Status state machine 409 on invalid transitions</li>
 *   <li>404 paths on unknown order/line</li>
 * </ul>
 */
@AutoConfigureMockMvc
class OrderHttpITest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    CustomerRepository customerRepo;

    @Autowired
    ProductRepository productRepo;

    private Long customerId;
    private Long productId;
    private String testIp;

    @BeforeEach
    void seed() {
        // Each test gets a fresh customer + product to avoid cross-test
        // interference. The unique-by-email + unique-by-name constraints
        // prevent collision with seeded data.
        Customer c = new Customer();
        c.setName("OrderIT Customer " + System.nanoTime());
        c.setEmail("order-it-" + System.nanoTime() + "@example.com");
        customerId = customerRepo.save(c).getId();

        Product p = new Product();
        p.setName("OrderIT Product " + System.nanoTime());
        p.setUnitPrice(new BigDecimal("9.99"));
        p.setStockQuantity(100);
        p.setUpdatedAt(Instant.now());
        productId = productRepo.save(p).getId();

        // Each test gets a unique X-Forwarded-For value so the per-IP
        // RateLimitingFilter assigns it its own token bucket. Without
        // this, the entire IT suite shares the 127.0.0.1 bucket
        // (depleted ≥ 100 requests in any single 60s window) and the
        // multi-call OrderHttpITest scenarios get 429s. A 10.x.x.x
        // private-range IP keyed off System.nanoTime() avoids any
        // collision with ITs run in parallel.
        long nano = System.nanoTime();
        testIp = "10." + ((nano >> 16) & 0xFF) + "." + ((nano >> 8) & 0xFF) + "." + (nano & 0xFF);
    }

    // ─── happy path : POST /orders → POST 2 lines → GET → DELETE line → GET ─

    @Test
    @Transactional
    void createOrderAddTwoLinesGetDeleteLineRecomputeTotal() throws Exception {
        // POST /orders → 201 Created with totalAmount = 0
        MvcResult created = mockMvc.perform(post("/orders")
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":%d}
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.customerId").value(customerId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(0))
                .andReturn();

        Long orderId = ((Number) com.jayway.jsonpath.JsonPath.read(
                created.getResponse().getContentAsString(), "$.id")).longValue();

        // POST line 1 (quantity=2) — total becomes 2 × 9.99 = 19.98
        MvcResult line1 = mockMvc.perform(post("/orders/{orderId}/lines", orderId)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":2}
                                """.formatted(productId)))
                .andExpect(status().isCreated())
                .andReturn();

        Long line1Id = ((Number) com.jayway.jsonpath.JsonPath.read(
                line1.getResponse().getContentAsString(), "$.id")).longValue();

        // POST line 2 (quantity=3) — total becomes (2 + 3) × 9.99 = 49.95
        mockMvc.perform(post("/orders/{orderId}/lines", orderId)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":3}
                                """.formatted(productId)))
                .andExpect(status().isCreated());

        // GET /orders/{id} — total = 49.95, 2 lines visible
        mockMvc.perform(get("/orders/{id}", orderId)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.totalAmount").value(49.95));

        // DELETE line 1 — total becomes 3 × 9.99 = 29.97
        mockMvc.perform(delete("/orders/{orderId}/lines/{lineId}", orderId, line1Id)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp))
                .andExpect(status().isNoContent());

        // GET again — total recomputed to 29.97
        mockMvc.perform(get("/orders/{id}", orderId)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(29.97));
    }

    // ─── validation 400 paths ────────────────────────────────────────────────

    @Test
    void post_missingCustomerId_returns400() throws Exception {
        mockMvc.perform(post("/orders")
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_negativeQuantityOnLine_returns400() throws Exception {
        // Create the order first
        Long orderId = createOrderHelper();

        mockMvc.perform(post("/orders/{orderId}/lines", orderId)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"productId":%d,"quantity":-1}
                                """.formatted(productId)))
                .andExpect(status().isBadRequest());
    }

    // ─── status state machine ───────────────────────────────────────────────

    @Test
    @Transactional
    void updateStatus_pendingToShipped_invalidTransition_returns409() throws Exception {
        Long orderId = createOrderHelper();

        // PENDING → SHIPPED : not allowed (must go through CONFIRMED)
        mockMvc.perform(put("/orders/{id}/status", orderId)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"SHIPPED"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @Transactional
    void updateStatus_pendingToConfirmed_validTransition_returns200() throws Exception {
        Long orderId = createOrderHelper();

        mockMvc.perform(put("/orders/{id}/status", orderId)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"CONFIRMED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    // ─── 404 paths ───────────────────────────────────────────────────────────

    @Test
    void get_unknownOrder_returns404() throws Exception {
        mockMvc.perform(get("/orders/{id}", 999_999_999L)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStatus_unknownOrder_returns404() throws Exception {
        mockMvc.perform(put("/orders/{id}/status", 999_999_998L)
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"status":"CONFIRMED"}
                                """))
                .andExpect(status().isNotFound());
    }

    // ─── auth ────────────────────────────────────────────────────────────────

    @Test
    void post_anonymous_returns401() throws Exception {
        mockMvc.perform(post("/orders")
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":1}
                                """))
                .andExpect(status().isUnauthorized());
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Long createOrderHelper() throws Exception {
        MvcResult r = mockMvc.perform(post("/orders")
                        .with(user("admin").roles("ADMIN"))
                        .header("X-Forwarded-For", testIp)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"customerId":%d}
                                """.formatted(customerId)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) com.jayway.jsonpath.JsonPath.read(
                r.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
