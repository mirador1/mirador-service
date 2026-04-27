package com.mirador.ml;

import ai.onnxruntime.OrtException;
import com.mirador.customer.Customer;
import com.mirador.customer.CustomerRepository;
import com.mirador.order.Order;
import com.mirador.order.OrderLine;
import com.mirador.order.OrderLineRepository;
import com.mirador.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * MCP {@code @Tool} surface for Customer Churn prediction.
 *
 * <p>Wraps {@link ChurnController}'s logic for exposure to LLM agents via
 * the in-process MCP server (per shared ADR-0062 — "produces vs accesses",
 * the backend exposes its own predictions in-process, no sidecar).
 *
 * <p>Single tool : {@code predict_customer_churn(customer_id)}. Returns
 * a {@link ChurnPredictionDto} OR a {@link NotFoundDto} when the
 * customer doesn't exist OR a {@link ServiceUnavailableDto} when the
 * model isn't loaded — soft errors instead of exceptions, so the LLM
 * gets a parseable signal it can recover from.
 */
@Service
public class ChurnMcpToolService {

    private static final Logger log = LoggerFactory.getLogger(ChurnMcpToolService.class);

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final ChurnFeatureExtractor extractor;
    private final ChurnPredictor predictor;

    public ChurnMcpToolService(
            CustomerRepository customers,
            OrderRepository orders,
            OrderLineRepository orderLines,
            ChurnFeatureExtractor extractor,
            ChurnPredictor predictor
    ) {
        this.customers = customers;
        this.orders = orders;
        this.orderLines = orderLines;
        this.extractor = extractor;
        this.predictor = predictor;
    }

    /** Soft-404 DTO — surfaced by the MCP layer without raising. */
    public record NotFoundDto(Long customerId, String message) { }

    /** Soft-503 DTO — surfaced when the ONNX model isn't loaded yet. */
    public record ServiceUnavailableDto(String message, String hint) { }

    @Tool(name = "predict_customer_churn",
            description = "Predict the probability that a given customer will churn (= no order "
                    + "in the next 90 days), using an ONNX-format model trained on historical "
                    + "Customer + Order data. Returns probability ∈ [0,1], a coarse risk band "
                    + "(LOW/MEDIUM/HIGH), the top 3 features that contributed most to the "
                    + "prediction, the model version, and the timestamp of the call. Returns a "
                    + "soft-404 / soft-503 DTO when the customer doesn't exist or the model "
                    + "isn't loaded — never raises.")
    public Object predictCustomerChurn(
            @ToolParam(description = "ID of the customer to score (matches Customer.id)") Long customerId
    ) {
        if (customerId == null) {
            return new NotFoundDto(null, "customer_id is required");
        }
        if (!predictor.isReady()) {
            return new ServiceUnavailableDto(
                    "Churn model not loaded",
                    "The .onnx artefact is missing — provision via the mirador-churn-model "
                    + "ConfigMap (see shared ADR-0062) and restart the pod, then retry.");
        }

        Customer customer = customers.findById(customerId).orElse(null);
        if (customer == null) {
            return new NotFoundDto(customerId, "customer not found");
        }

        List<Order> customerOrders = orders.findByCustomerIdOrderByCreatedAtDesc(customerId);
        List<OrderLine> customerLines = customerOrders.stream()
                .flatMap(order -> orderLines.findByOrderIdOrderByIdAsc(order.getId()).stream())
                .toList();

        float[] features = extractor.extract(customer, customerOrders, customerLines);

        try {
            double probability = predictor.predictProbability(features);
            return new ChurnPredictionDto(
                    customerId,
                    probability,
                    RiskBand.classify(probability),
                    List.of("days_since_last_order", "total_revenue_90d", "order_frequency"),
                    predictor.modelVersion(),
                    Instant.now()
            );
        } catch (OrtException e) {
            log.error("churn MCP tool inference failure for customerId={} : {}", customerId, e.getMessage(), e);
            return new ServiceUnavailableDto(
                    "Inference failed",
                    "Runtime error during ONNX run — see server logs (likely NaN inputs or "
                    + "model file corruption). Retry with a customer that has at least one order.");
        }
    }
}
