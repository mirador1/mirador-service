package org.iris.ml;

import ai.onnxruntime.OrtException;
import org.iris.customer.Customer;
import org.iris.customer.CustomerRepository;
import org.iris.order.Order;
import org.iris.order.OrderLine;
import org.iris.order.OrderLineRepository;
import org.iris.order.OrderRepository;
import org.iris.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the MCP tool wrapper around {@link ChurnPredictor}.
 *
 * <p>These cover the soft-error paths : missing customer (→ NotFoundDto),
 * model not loaded (→ ServiceUnavailableDto). The happy path is covered
 * end-to-end in {@code McpServerITest} when an .onnx artefact is
 * provisioned ; here we validate that the wrapper never throws and
 * always returns a parseable DTO for the LLM caller.
 */
class ChurnMcpToolServiceTest {

    private CustomerRepository customers;
    private OrderRepository orders;
    private OrderLineRepository orderLines;
    private ChurnFeatureExtractor extractor;
    private ChurnPredictor predictor;

    private ChurnMcpToolService tool;

    @BeforeEach
    void setUp() {
        customers = Mockito.mock(CustomerRepository.class);
        orders = Mockito.mock(OrderRepository.class);
        orderLines = Mockito.mock(OrderLineRepository.class);
        extractor = new ChurnFeatureExtractor(
                Clock.fixed(Instant.parse("2026-04-27T00:00:00Z"), ZoneOffset.UTC)
        );
        // Predictor pointed at a missing path → isReady() = false.
        predictor = new ChurnPredictor("/nonexistent/churn.onnx", "v0-test");
        predictor.loadModel();

        tool = new ChurnMcpToolService(customers, orders, orderLines, extractor, predictor);
    }

    @Test
    void returnsServiceUnavailableWhenModelNotLoaded() {
        when(customers.findById(1L)).thenReturn(Optional.of(buildCustomer()));
        when(orders.findByCustomerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        Object result = tool.predictCustomerChurn(1L);

        assertThat(result).isInstanceOf(ChurnMcpToolService.ServiceUnavailableDto.class);
        ChurnMcpToolService.ServiceUnavailableDto dto = (ChurnMcpToolService.ServiceUnavailableDto) result;
        assertThat(dto.message()).contains("not loaded");
        assertThat(dto.hint()).contains("ConfigMap");
    }

    @Test
    void returnsNotFoundWhenCustomerIdIsNull() {
        Object result = tool.predictCustomerChurn(null);

        assertThat(result).isInstanceOf(ChurnMcpToolService.NotFoundDto.class);
        ChurnMcpToolService.NotFoundDto dto = (ChurnMcpToolService.NotFoundDto) result;
        assertThat(dto.customerId()).isNull();
        assertThat(dto.message()).contains("required");
    }

    @Test
    void returnsServiceUnavailableBeforeNotFoundCheck() {
        // When the model isn't loaded, the early return kicks in BEFORE
        // we hit the customer repository. The customer-not-found path is
        // unreachable in this state ; documented intent.
        when(customers.findById(999L)).thenReturn(Optional.empty());

        Object result = tool.predictCustomerChurn(999L);

        // Model-not-loaded wins over not-found because it indicates a
        // broader unavailability state ; retrying with a different ID
        // wouldn't help. UX is clearer this way.
        assertThat(result).isInstanceOf(ChurnMcpToolService.ServiceUnavailableDto.class);
    }

    // ─── happy paths with a mocked, ready predictor ──────────────────────────

    @Test
    void returnsChurnPredictionDto_whenModelReadyAndCustomerExists() throws OrtException {
        // Pinned : when predictor.isReady() returns true and a customer is
        // present, the tool returns a fully-formed ChurnPredictionDto with
        // the predicted probability, risk band, top features, model version.
        ChurnPredictor mockPredictor = Mockito.mock(ChurnPredictor.class);
        when(mockPredictor.isReady()).thenReturn(true);
        when(mockPredictor.predictProbability(any(float[].class))).thenReturn(0.82);
        when(mockPredictor.modelVersion()).thenReturn("v3-2026-04-27");
        ChurnMcpToolService toolWithReady = new ChurnMcpToolService(
                customers, orders, orderLines, extractor, mockPredictor);

        Customer alice = buildCustomer();
        when(customers.findById(1L)).thenReturn(Optional.of(alice));
        Order o = order(101L, 1L);
        when(orders.findByCustomerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(o));
        OrderLine line = orderLine(1L, 101L);
        when(orderLines.findByOrderIdOrderByIdAsc(101L)).thenReturn(List.of(line));

        Object result = toolWithReady.predictCustomerChurn(1L);

        assertThat(result).isInstanceOf(ChurnPredictionDto.class);
        ChurnPredictionDto dto = (ChurnPredictionDto) result;
        assertThat(dto.customerId()).isEqualTo(1L);
        assertThat(dto.probability()).isEqualTo(0.82);
        assertThat(dto.riskBand()).isEqualTo(RiskBand.HIGH);
        assertThat(dto.modelVersion()).isEqualTo("v3-2026-04-27");
        assertThat(dto.topFeatures()).containsExactly(
                "days_since_last_order", "total_revenue_90d", "order_frequency");
    }

    @Test
    void returnsNotFoundDto_whenModelReadyButCustomerMissing() {
        // The customer-not-found path is reachable only when the model is
        // ready (the not-loaded branch short-circuits earlier).
        ChurnPredictor mockPredictor = Mockito.mock(ChurnPredictor.class);
        when(mockPredictor.isReady()).thenReturn(true);
        ChurnMcpToolService toolWithReady = new ChurnMcpToolService(
                customers, orders, orderLines, extractor, mockPredictor);
        when(customers.findById(999L)).thenReturn(Optional.empty());

        Object result = toolWithReady.predictCustomerChurn(999L);

        assertThat(result).isInstanceOf(ChurnMcpToolService.NotFoundDto.class);
        ChurnMcpToolService.NotFoundDto dto = (ChurnMcpToolService.NotFoundDto) result;
        assertThat(dto.customerId()).isEqualTo(999L);
        assertThat(dto.message()).contains("not found");
    }

    @Test
    void returnsServiceUnavailableDto_whenInferenceThrowsOrtException() throws OrtException {
        // Runtime errors (NaN inputs, model file corruption mid-run) are
        // caught and surface as a soft-503. The LLM caller can interpret
        // the hint and retry with a different customer.
        ChurnPredictor mockPredictor = Mockito.mock(ChurnPredictor.class);
        when(mockPredictor.isReady()).thenReturn(true);
        when(mockPredictor.predictProbability(any(float[].class)))
                .thenThrow(new OrtException(OrtException.OrtErrorCode.ORT_FAIL, "nan-encountered"));
        ChurnMcpToolService toolWithReady = new ChurnMcpToolService(
                customers, orders, orderLines, extractor, mockPredictor);
        when(customers.findById(1L)).thenReturn(Optional.of(buildCustomer()));
        when(orders.findByCustomerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        Object result = toolWithReady.predictCustomerChurn(1L);

        assertThat(result).isInstanceOf(ChurnMcpToolService.ServiceUnavailableDto.class);
        ChurnMcpToolService.ServiceUnavailableDto dto =
                (ChurnMcpToolService.ServiceUnavailableDto) result;
        assertThat(dto.message()).isEqualTo("Inference failed");
        assertThat(dto.hint()).contains("Runtime error");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private Customer buildCustomer() {
        Customer c = new Customer();
        c.setId(1L);
        c.setEmail("alice@gmail.com");
        c.setName("Alice");
        c.setCreatedAt(Instant.parse("2025-12-01T00:00:00Z"));
        return c;
    }

    private static Order order(Long id, Long customerId) {
        Order o = new Order();
        o.setId(id);
        o.setCustomerId(customerId);
        o.setStatus(OrderStatus.SHIPPED);
        o.setTotalAmount(new BigDecimal("99.00"));
        o.setCreatedAt(Instant.parse("2026-04-15T00:00:00Z"));
        o.setUpdatedAt(Instant.parse("2026-04-15T00:00:00Z"));
        return o;
    }

    private static OrderLine orderLine(Long id, Long orderId) {
        OrderLine l = new OrderLine();
        l.setId(id);
        l.setOrderId(orderId);
        l.setProductId(42L);
        l.setQuantity(1);
        l.setUnitPriceAtOrder(new BigDecimal("99.00"));
        l.setCreatedAt(Instant.parse("2026-04-15T00:00:00Z"));
        return l;
    }
}
