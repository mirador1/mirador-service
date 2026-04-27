package com.mirador.ml;

import com.mirador.customer.Customer;
import com.mirador.customer.CustomerRepository;
import com.mirador.order.OrderLineRepository;
import com.mirador.order.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

    private Customer buildCustomer() {
        Customer c = new Customer();
        c.setId(1L);
        c.setEmail("alice@gmail.com");
        c.setName("Alice");
        c.setCreatedAt(Instant.parse("2025-12-01T00:00:00Z"));
        return c;
    }
}
