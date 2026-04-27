package com.mirador.ml;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Result of a churn prediction call — returned by
 * {@link ChurnController} and the {@code predict_customer_churn} MCP tool.
 *
 * @param customerId      the predicted customer (echoed for client-side
 *                        correlation).
 * @param probability     ∈ [0, 1] — output of {@code sigmoid(logits)}.
 * @param riskBand        coarse classification (LOW / MEDIUM / HIGH) per
 *                        {@link RiskBand#classify(double)}.
 * @param topFeatures     up to 3 feature names ranked by absolute
 *                        contribution to the prediction (rough proxy ;
 *                        a full SHAP explanation is Phase E work).
 * @param modelVersion    the ONNX file version actually used at
 *                        inference time — promoted via the
 *                        {@code mirador-churn-model} ConfigMap (shared
 *                        ADR-0062).
 * @param predictedAt     server-side timestamp of the inference call,
 *                        for audit + drift-monitoring correlation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChurnPredictionDto(
        Long customerId,
        double probability,
        RiskBand riskBand,
        List<String> topFeatures,
        String modelVersion,
        Instant predictedAt
) {
}
