package com.mirador.ml;

import ai.onnxruntime.OrtException;
import com.mirador.customer.Customer;
import com.mirador.customer.CustomerRepository;
import com.mirador.order.Order;
import com.mirador.order.OrderLine;
import com.mirador.order.OrderLineRepository;
import com.mirador.order.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChurnController} — the REST entry point for
 * the Customer Churn prediction (Phase B of shared ADR-0061). Closes
 * the 0% coverage gap on this class — the existing tests covered
 * {@link ChurnPredictor}, {@link ChurnFeatureExtractor},
 * {@link ChurnMcpToolService}, and {@link RiskBand} but not the HTTP
 * layer that wires them together.
 *
 * <p>Covers the four exit paths the controller can produce :
 *
 * <ol>
 *   <li>503 when the model isn't ready (ConfigMap not provisioned).</li>
 *   <li>404 when the customer doesn't exist (model IS ready).</li>
 *   <li>200 with the full DTO on the happy path (all mocks aligned).</li>
 *   <li>500 if {@code OrtException} surfaces during inference.</li>
 * </ol>
 */
class ChurnControllerTest {

    private CustomerRepository customers;
    private OrderRepository orders;
    private OrderLineRepository orderLines;
    private ChurnFeatureExtractor extractor;
    private ChurnPredictor predictor;
    private ChurnController controller;

    @BeforeEach
    void setUp() {
        customers = mock(CustomerRepository.class);
        orders = mock(OrderRepository.class);
        orderLines = mock(OrderLineRepository.class);
        extractor = mock(ChurnFeatureExtractor.class);
        predictor = mock(ChurnPredictor.class);
        controller = new ChurnController(customers, orders, orderLines, extractor, predictor);
    }

    @Test
    void predict_modelNotReady_returns503WithoutTouchingDb() throws OrtException {
        when(predictor.isReady()).thenReturn(false);

        ResponseEntity<ChurnPredictionDto> response = controller.predict(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNull();
        // Critical : we should NOT load the customer when the model
        // isn't ready — saves a DB round-trip on every 503.
        verify(customers, never()).findById(anyLong());
        verify(predictor, never()).predictProbability(any());
    }

    @Test
    void predict_customerMissing_returns404() throws OrtException {
        when(predictor.isReady()).thenReturn(true);
        when(customers.findById(99L)).thenReturn(Optional.empty());

        ResponseEntity<ChurnPredictionDto> response = controller.predict(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        verify(predictor, never()).predictProbability(any());
    }

    @Test
    void predict_happyPath_returns200WithFullDto() throws OrtException {
        when(predictor.isReady()).thenReturn(true);
        when(predictor.modelVersion()).thenReturn("v3-2026-04-27");
        when(customers.findById(42L)).thenReturn(Optional.of(customer(42L)));

        Order order = order(100L, 42L);
        OrderLine line = line(1L, 100L);
        when(orders.findByCustomerIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(order));
        when(orderLines.findByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(line));

        // Feature vector chosen so the top-3 absolute-value ranking is
        // deterministic : days_since_last_order (idx 0) > total_revenue_90d
        // (idx 2) > order_frequency (idx 4).
        float[] features = {180f, 0f, 50f, 10f, 30f, 0f, 0f, 5f};
        when(extractor.extract(any(Customer.class), any(), any())).thenReturn(features);
        when(predictor.predictProbability(features)).thenReturn(0.731);

        ResponseEntity<ChurnPredictionDto> response = controller.predict(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChurnPredictionDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.customerId()).isEqualTo(42L);
        assertThat(dto.probability()).isEqualTo(0.731);
        assertThat(dto.riskBand()).isEqualTo(RiskBand.HIGH); // 0.731 > 0.7
        assertThat(dto.modelVersion()).isEqualTo("v3-2026-04-27");
        assertThat(dto.predictedAt()).isNotNull();
        // Top-3 by absolute value of the feature vector above.
        assertThat(dto.topFeatures()).containsExactly(
                "days_since_last_order",
                "total_revenue_90d",
                "order_frequency"
        );
    }

    @Test
    void predict_lowProbability_classifiesAsLowBand() throws OrtException {
        when(predictor.isReady()).thenReturn(true);
        when(predictor.modelVersion()).thenReturn("v0");
        when(customers.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(orders.findByCustomerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(extractor.extract(any(Customer.class), any(), any()))
                .thenReturn(new float[8]);
        when(predictor.predictProbability(any())).thenReturn(0.10);

        ResponseEntity<ChurnPredictionDto> response = controller.predict(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChurnPredictionDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.riskBand()).isEqualTo(RiskBand.LOW);
    }

    @Test
    void predict_inferenceThrowsOrtException_returns500() throws OrtException {
        when(predictor.isReady()).thenReturn(true);
        when(predictor.modelVersion()).thenReturn("v0");
        when(customers.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(orders.findByCustomerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        when(extractor.extract(any(Customer.class), any(), any()))
                .thenReturn(new float[8]);
        // Simulate ONNX runtime failure (rare ; usually NaN feature shape).
        when(predictor.predictProbability(any()))
                .thenThrow(new OrtException("simulated ONNX failure"));

        ResponseEntity<ChurnPredictionDto> response = controller.predict(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void predict_flatMapsLinesAcrossMultipleOrders() throws OrtException {
        // Verify the flatMap of orders → orderLines is wired correctly :
        // one customer with 2 orders, each with 1 line, must produce a
        // single concatenated list when calling extractor.extract.
        when(predictor.isReady()).thenReturn(true);
        when(predictor.modelVersion()).thenReturn("v0");
        when(customers.findById(1L)).thenReturn(Optional.of(customer(1L)));

        Order o1 = order(100L, 1L);
        Order o2 = order(101L, 1L);
        OrderLine l1 = line(1L, 100L);
        OrderLine l2 = line(2L, 101L);
        when(orders.findByCustomerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(o1, o2));
        when(orderLines.findByOrderIdOrderByIdAsc(100L)).thenReturn(List.of(l1));
        when(orderLines.findByOrderIdOrderByIdAsc(101L)).thenReturn(List.of(l2));
        when(extractor.extract(any(Customer.class), any(), any())).thenReturn(new float[8]);
        when(predictor.predictProbability(any())).thenReturn(0.5);

        ResponseEntity<ChurnPredictionDto> response = controller.predict(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Both order-line repos were queried — flatMap is doing its job.
        verify(orderLines).findByOrderIdOrderByIdAsc(100L);
        verify(orderLines).findByOrderIdOrderByIdAsc(101L);
    }

    private static Customer customer(Long id) {
        Customer c = new Customer();
        c.setId(id);
        c.setName("Customer-" + id);
        c.setEmail("c" + id + "@example.com");
        c.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return c;
    }

    private static Order order(Long id, Long customerId) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(customerId);
        o.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
        o.setTotalAmount(new BigDecimal("19.99"));
        return o;
    }

    private static OrderLine line(Long id, Long orderId) {
        OrderLine l = new OrderLine();
        l.setId(id);
        l.setOrderId(orderId);
        l.setProductId(42L);
        l.setQuantity(1);
        l.setUnitPriceAtOrder(new BigDecimal("9.99"));
        return l;
    }
}
