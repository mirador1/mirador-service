package com.mirador.ml;

import com.mirador.customer.Customer;
import com.mirador.order.Order;
import com.mirador.order.OrderLine;
import com.mirador.order.OrderStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for {@link ChurnFeatureExtractor} — feature engineering
 * parity with the Python sibling.
 *
 * <p>The 8 features are positional in the ONNX input tensor (per shared
 * ADR-0061) ; if these tests fail, the cross-language smoke test
 * (Phase G) would also fail because the Java + Python feature vectors
 * would no longer match.
 */
class ChurnFeatureExtractorTest {

    /** Reference time pinned for reproducibility — same as Python's tests/ml/test_features.py. */
    private static final Instant NOW = Instant.parse("2026-04-27T00:00:00Z");

    private final ChurnFeatureExtractor extractor = new ChurnFeatureExtractor(
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void emailDomainClassifierMatchesPython() {
        // 0 = corporate (default), 1 = mainstream, 2 = disposable, 3 = unknown.
        assertThat(ChurnFeatureExtractor.classifyEmailDomain("alice@gmail.com")).isEqualTo(1);
        assertThat(ChurnFeatureExtractor.classifyEmailDomain("alice@TEMPMAIL.com")).isEqualTo(2);
        assertThat(ChurnFeatureExtractor.classifyEmailDomain("alice@acme-corp.example")).isEqualTo(0);
        assertThat(ChurnFeatureExtractor.classifyEmailDomain("")).isEqualTo(3);
        assertThat(ChurnFeatureExtractor.classifyEmailDomain(null)).isEqualTo(3);
        assertThat(ChurnFeatureExtractor.classifyEmailDomain("not-an-email")).isEqualTo(3);
        assertThat(ChurnFeatureExtractor.classifyEmailDomain("alice@")).isEqualTo(3);
    }

    @Test
    void extractReturnsExactlyEightFeatures() {
        Customer customer = customer(1L, "alice@gmail.com", NOW.minus(200, ChronoUnit.DAYS));
        Order order = order(100L, 1L, NOW.minus(10, ChronoUnit.DAYS), "50.00");
        OrderLine line = line(1L, 100L, 1L);

        float[] features = extractor.extract(customer, List.of(order), List.of(line));

        assertThat(features).hasSize(ChurnFeatureExtractor.N_FEATURES);
    }

    @Test
    void revenueWindowsAreInclusive() {
        Customer customer = customer(1L, "a@gmail.com", NOW.minus(400, ChronoUnit.DAYS));
        // Two orders : one inside 30d, one inside 90d-only, one outside both.
        Order recent = order(100L, 1L, NOW.minus(10, ChronoUnit.DAYS), "50.00");
        Order older = order(101L, 1L, NOW.minus(60, ChronoUnit.DAYS), "30.00");
        Order ancient = order(102L, 1L, NOW.minus(200, ChronoUnit.DAYS), "20.00");

        float[] features = extractor.extract(customer, List.of(recent, older, ancient), List.of());

        // Index 1 = total_revenue_30d, 2 = 90d, 3 = 365d.
        assertThat(features[1]).isCloseTo(50.0f, offset(1e-6f));
        assertThat(features[2]).isCloseTo(80.0f, offset(1e-6f));
        assertThat(features[3]).isCloseTo(100.0f, offset(1e-6f));
    }

    @Test
    void customerWithNoOrdersYieldsZeroAggregates() {
        Customer customer = customer(1L, "a@gmail.com", NOW.minus(50, ChronoUnit.DAYS));

        float[] features = extractor.extract(customer, List.of(), List.of());

        // No NaN, sane defaults.
        for (float f : features) {
            assertThat(f).isFinite();
        }
        assertThat(features[1]).isEqualTo(0.0f);  // revenue_30d
        assertThat(features[2]).isEqualTo(0.0f);  // revenue_90d
        assertThat(features[3]).isEqualTo(0.0f);  // revenue_365d
        assertThat(features[4]).isEqualTo(0.0f);  // order_frequency
        assertThat(features[5]).isEqualTo(0.0f);  // cart_diversity
    }

    @Test
    void cartDiversityRatiosDistinctOverTotal() {
        Customer customer = customer(1L, "a@gmail.com", NOW.minus(100, ChronoUnit.DAYS));
        Order order = order(100L, 1L, NOW.minus(5, ChronoUnit.DAYS), "10.00");
        // 3 lines, 2 distinct products → diversity = 2/3.
        OrderLine line1 = line(1L, 100L, 10L);
        OrderLine line2 = line(2L, 100L, 11L);
        OrderLine line3 = line(3L, 100L, 10L);

        float[] features = extractor.extract(customer, List.of(order), List.of(line1, line2, line3));

        assertThat(features[5]).isCloseTo(2.0f / 3.0f, offset(1e-6f));
    }

    @Test
    void daysSinceLastOrderClipsToZero() {
        // Customer's last order is "future" (synthetic edge case) — clip to 0.
        Customer customer = customer(1L, "a@gmail.com", NOW.minus(100, ChronoUnit.DAYS));
        Order future = order(100L, 1L, NOW.plus(5, ChronoUnit.DAYS), "10.00");

        float[] features = extractor.extract(customer, List.of(future), List.of());

        assertThat(features[0]).isEqualTo(0.0f);
    }

    private Customer customer(Long id, String email, Instant createdAt) {
        Customer c = new Customer();
        c.setId(id);
        c.setEmail(email);
        c.setName("Test " + id);
        c.setCreatedAt(createdAt);
        return c;
    }

    private Order order(Long id, Long customerId, Instant createdAt, String amount) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(customerId);
        o.setCreatedAt(createdAt);
        o.setTotalAmount(new BigDecimal(amount));
        o.setStatus(OrderStatus.PENDING);
        return o;
    }

    private OrderLine line(Long id, Long orderId, Long productId) {
        OrderLine l = new OrderLine();
        l.setId(id);
        l.setOrderId(orderId);
        l.setProductId(productId);
        l.setQuantity(1);
        l.setUnitPriceAtOrder(new BigDecimal("10.00"));
        return l;
    }
}
