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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * REST endpoint exposing the Customer Churn prediction.
 *
 * <p>Single endpoint :
 * <pre>POST /customers/{id}/churn-prediction</pre>
 *
 * <p>Loads the customer + their order history from the JPA repositories,
 * runs feature engineering through {@link ChurnFeatureExtractor}, calls
 * {@link ChurnPredictor#predictProbability(float[])}, and returns a
 * {@link ChurnPredictionDto} with probability + risk band + top-3
 * contributing features (rough proxy ; full SHAP explanation is Phase E).
 *
 * <p>Returns {@code 404} if the customer doesn't exist, {@code 503} if
 * the ONNX model isn't loaded (e.g. ConfigMap not yet provisioned per
 * shared ADR-0062), and {@code 200} with the prediction otherwise.
 */
@RestController
@RequestMapping("/customers/{id}")
public class ChurnController {

    private static final Logger log = LoggerFactory.getLogger(ChurnController.class);

    /** Canonical feature names — same order as {@link ChurnFeatureExtractor#extract}. */
    private static final String[] FEATURE_NAMES = {
            "days_since_last_order",
            "total_revenue_30d",
            "total_revenue_90d",
            "total_revenue_365d",
            "order_frequency",
            "cart_diversity",
            "email_domain_class",
            "customer_lifetime_days",
    };

    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final ChurnFeatureExtractor extractor;
    private final ChurnPredictor predictor;

    public ChurnController(
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

    @PostMapping("/churn-prediction")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ChurnPredictionDto> predict(@PathVariable("id") Long customerId) {
        if (!predictor.isReady()) {
            log.debug("churn-prediction request rejected — model not loaded (customerId={})", customerId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        Optional<Customer> maybeCustomer = customers.findById(customerId);
        if (maybeCustomer.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Customer customer = maybeCustomer.get();

        List<Order> customerOrders = orders.findByCustomerIdOrderByCreatedAtDesc(customerId);
        List<OrderLine> customerLines = customerOrders.stream()
                .flatMap(order -> orderLines.findByOrderIdOrderByIdAsc(order.getId()).stream())
                .toList();

        float[] features = extractor.extract(customer, customerOrders, customerLines);

        try {
            double probability = predictor.predictProbability(features);
            return ResponseEntity.ok(buildDto(customerId, probability, features));
        } catch (OrtException e) {
            log.error("churn-prediction inference failure for customerId={} : {}", customerId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private ChurnPredictionDto buildDto(Long customerId, double probability, float[] features) {
        // "Top-3 contributing features" — rough proxy ranking by absolute
        // value. A full SHAP explanation requires running the model
        // multiple times against modified inputs (Phase E work).
        List<String> topFeatures = IntStream.range(0, features.length)
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> -Math.abs(features[i])))
                .limit(3)
                .map(i -> FEATURE_NAMES[i])
                .toList();

        return new ChurnPredictionDto(
                customerId,
                probability,
                RiskBand.classify(probability),
                topFeatures,
                predictor.modelVersion(),
                Instant.now()
        );
    }
}
