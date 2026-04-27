package com.mirador.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads an ONNX-format Customer Churn predictor at startup and exposes a
 * {@link #predictProbability(float[])} entry-point used by
 * {@link ChurnController} (REST) and {@link ChurnMcpToolService} (MCP @Tool).
 *
 * <p>The model file path is configurable via the {@code mirador.churn.model-path}
 * property — defaults to {@code /etc/models/churn_predictor.onnx} which
 * matches the Kubernetes ConfigMap mount per shared ADR-0062. Local dev
 * can override to {@code ./.models/churn_predictor.onnx} (the path
 * {@code mirador_service.ml.train_churn} writes to by default).
 *
 * <p>Sigmoid is applied IN THIS CODE — the ONNX graph emits raw logits
 * (per shared ADR-0061 §"ONNX export contract"). Keeps the export
 * simple and lets us swap calibration (Platt scaling, isotonic) without
 * re-export.
 *
 * <p>Threading model : ONNX Runtime's {@link OrtSession} is thread-safe
 * for concurrent {@code run()} calls. We hold a single instance for the
 * application lifetime and let Spring serve it as a singleton.
 *
 * <p>Failure model : if the model file is missing at startup, this bean
 * still instantiates but {@link #isReady()} returns {@code false} and
 * {@link #predictProbability(float[])} throws
 * {@link IllegalStateException}. Callers (REST + MCP) check
 * {@code isReady()} and return a graceful 503 / "model unavailable"
 * response — the absence of a model must not crash the whole app.
 */
@Service
public class ChurnPredictor {

    private static final Logger log = LoggerFactory.getLogger(ChurnPredictor.class);

    /** Default ONNX path — matches the K8s ConfigMap mount per ADR-0062. */
    public static final String DEFAULT_MODEL_PATH = "/etc/models/churn_predictor.onnx";

    /** Input tensor name declared in the ONNX graph (per ADR-0061). */
    public static final String INPUT_NAME = "input";

    /** Output tensor name declared in the ONNX graph (raw logits). */
    public static final String OUTPUT_NAME = "logits";

    private final Path modelPath;
    private final String modelVersionLabel;
    private OrtEnvironment environment;
    private OrtSession session;

    public ChurnPredictor(
            @Value("${mirador.churn.model-path:" + DEFAULT_MODEL_PATH + "}") String modelPath,
            @Value("${mirador.churn.model-version:unspecified}") String modelVersionLabel
    ) {
        this.modelPath = Paths.get(modelPath);
        this.modelVersionLabel = modelVersionLabel;
    }

    @PostConstruct
    void loadModel() {
        if (!Files.exists(modelPath)) {
            log.warn("churn model NOT loaded — file missing at {} ; predictions will return 503 until the model is provisioned via the mirador-churn-model ConfigMap (shared ADR-0062)",
                    modelPath);
            return;
        }
        try {
            this.environment = OrtEnvironment.getEnvironment();
            this.session = environment.createSession(modelPath.toString());
            log.info("churn model loaded — path={} size={}B version={}",
                    modelPath, Files.size(modelPath), modelVersionLabel);
        } catch (OrtException | IOException e) {
            log.error("churn model failed to load — path={} reason={}", modelPath, e.getMessage(), e);
            this.session = null;
        }
    }

    @PreDestroy
    void closeSession() {
        if (session != null) {
            try {
                session.close();
            } catch (OrtException e) {
                log.warn("churn model session close failed (best-effort) : {}", e.getMessage());
            }
        }
    }

    /** True iff the ONNX model is loaded and ready to serve predictions. */
    public boolean isReady() {
        return session != null;
    }

    /** The current model version label — for audit + ChurnPredictionDto. */
    public String modelVersion() {
        return modelVersionLabel;
    }

    /**
     * Run the model on a single 8-feature vector and return the
     * sigmoid-of-logit probability of churn.
     *
     * @param features float[{@value ChurnFeatureExtractor#N_FEATURES}] in
     *                 canonical order (see {@link ChurnFeatureExtractor}).
     * @return probability ∈ [0, 1].
     * @throws IllegalStateException if the model isn't loaded.
     * @throws IllegalArgumentException if the feature vector is the wrong length.
     * @throws OrtException if the runtime fails (rare ; usually NaN inputs).
     */
    public double predictProbability(float[] features) throws OrtException {
        if (!isReady()) {
            throw new IllegalStateException(
                    "Churn model not loaded — provision the .onnx via the mirador-churn-model ConfigMap "
                    + "(see shared ADR-0062) and restart the pod.");
        }
        if (features == null || features.length != ChurnFeatureExtractor.N_FEATURES) {
            throw new IllegalArgumentException(
                    "feature vector must have exactly " + ChurnFeatureExtractor.N_FEATURES + " elements, got "
                    + (features == null ? "null" : features.length));
        }

        long[] shape = { 1L, ChurnFeatureExtractor.N_FEATURES };
        try (OnnxTensor inputTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features), shape)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put(INPUT_NAME, inputTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                Object value = result.get(0).getValue();
                if (!(value instanceof float[][] logits) || logits.length == 0 || logits[0].length == 0) {
                    throw new OrtException(
                            OrtException.OrtErrorCode.ORT_FAIL,
                            "unexpected ONNX output shape — expected float[1][1], got "
                            + (value == null ? "null" : value.getClass().getName()));
                }
                return sigmoid(logits[0][0]);
            }
        }
    }

    /** Numerically-stable sigmoid for arbitrary float input. */
    private static double sigmoid(float logit) {
        if (logit >= 0.0f) {
            double exp = Math.exp(-logit);
            return 1.0 / (1.0 + exp);
        }
        double exp = Math.exp(logit);
        return exp / (1.0 + exp);
    }
}
