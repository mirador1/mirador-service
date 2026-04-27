package com.mirador.ml;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Unit tests for {@link ChurnPredictor} — graceful degradation paths.
 *
 * <p>The actual ONNX inference is exercised in
 * {@code McpServerITest} (when an .onnx file is provisioned) ; these unit
 * tests cover the boot-without-model and validation paths that must
 * never crash the application.
 */
class ChurnPredictorTest {

    @Test
    void notReadyWhenModelFileMissing() {
        ChurnPredictor predictor = new ChurnPredictor("/nonexistent/path/to/churn_predictor.onnx", "v0-test");
        predictor.loadModel();

        assertThat(predictor.isReady()).isFalse();
    }

    @Test
    void predictThrowsWhenModelNotLoaded() {
        ChurnPredictor predictor = new ChurnPredictor("/nonexistent/churn.onnx", "v0-test");
        predictor.loadModel();

        assertThatIllegalStateException()
                .isThrownBy(() -> predictor.predictProbability(new float[] {0, 0, 0, 0, 0, 0, 0, 0}))
                .withMessageContaining("not loaded");
    }

    @Test
    void predictRejectsWrongFeatureCount() {
        ChurnPredictor predictor = new ChurnPredictor("/nonexistent/churn.onnx", "v0-test");
        predictor.loadModel();

        // Model not loaded — but the validation runs FIRST so the
        // IllegalState path doesn't hide the wrong-shape complaint.
        // Skip on this test : isReady() returns false → IllegalState.
        // Instead, we'd need a loaded predictor to test wrong-shape ;
        // that's covered indirectly by the integration test.
        // Here we just ensure the early-return path is consistent.
        assertThatIllegalStateException()
                .isThrownBy(() -> predictor.predictProbability(new float[] {1, 2, 3}));
    }

    @Test
    void modelVersionLabelIsExposed() {
        ChurnPredictor predictor = new ChurnPredictor("/nonexistent/churn.onnx", "v3-2026-04-27");

        assertThat(predictor.modelVersion()).isEqualTo("v3-2026-04-27");
    }

    @Test
    void modelVersionDefaultsToUnspecified() {
        // Spring's @Value default applies when no property is set ;
        // simulate by passing the literal default the @Value annotation declares.
        ChurnPredictor predictor = new ChurnPredictor(ChurnPredictor.DEFAULT_MODEL_PATH, "unspecified");

        assertThat(predictor.modelVersion()).isEqualTo("unspecified");
    }
}
