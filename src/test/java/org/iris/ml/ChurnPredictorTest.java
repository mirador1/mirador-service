package org.iris.ml;

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

    // ─── happy path with a real ONNX model fixture ────────────────────────────

    /**
     * Test fixture path. Built once via build_churn_test_onnx.py — a 166-byte
     * model that maps an 8-feature input to a single logit equal to the sum
     * of the inputs (single MatMul with all-ones weights). Reproducible :
     * see bin/build-churn-test-onnx.sh in the repo for the regen recipe.
     *
     * <p>Working directory is the project root during {@code mvn test}, so
     * the relative path resolves correctly.
     */
    private static final String TEST_ONNX_PATH =
            "src/test/resources/churn-predictor-test.onnx";

    @org.junit.jupiter.api.Test
    void readyAndPredict_withRealOnnxFixture() throws ai.onnxruntime.OrtException {
        // Pinned : when the model loads, isReady() flips to true and
        // predictProbability returns a value in [0,1]. The fixture is
        // logit = sum(features) with no bias, so an all-zero vector
        // gives sigmoid(0) = 0.5 exactly. This pins the wiring (model
        // load → OrtSession.run → sigmoid) end-to-end.
        ChurnPredictor predictor = new ChurnPredictor(TEST_ONNX_PATH, "v0-test-fixture");
        predictor.loadModel();

        org.assertj.core.api.Assertions.assertThat(predictor.isReady()).isTrue();
        org.assertj.core.api.Assertions.assertThat(predictor.modelVersion())
                .isEqualTo("v0-test-fixture");

        double prob = predictor.predictProbability(
                new float[] {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});
        org.assertj.core.api.Assertions.assertThat(prob)
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-4));

        predictor.closeSession();
    }

    @org.junit.jupiter.api.Test
    void predictProbability_highSumPushesProbabilityToOne() throws ai.onnxruntime.OrtException {
        // Sum(features) = 4.0 → sigmoid(4) ≈ 0.982. Pinned so a future
        // change that drops the sigmoid (or applies it twice) is caught.
        ChurnPredictor predictor = new ChurnPredictor(TEST_ONNX_PATH, "v0-test-fixture");
        predictor.loadModel();

        double prob = predictor.predictProbability(
                new float[] {4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});

        org.assertj.core.api.Assertions.assertThat(prob)
                .isCloseTo(0.9820, org.assertj.core.data.Offset.offset(1e-3));

        predictor.closeSession();
    }

    @org.junit.jupiter.api.Test
    void predictProbability_negativeSumDropsProbabilityToZero() throws ai.onnxruntime.OrtException {
        // Sum(features) = -4.0 → sigmoid(-4) ≈ 0.018. Mirrors the
        // numerical-stability branch of sigmoid (negative-logit path
        // uses exp(logit) / (1 + exp(logit)), distinct from the
        // positive-logit branch).
        ChurnPredictor predictor = new ChurnPredictor(TEST_ONNX_PATH, "v0-test-fixture");
        predictor.loadModel();

        double prob = predictor.predictProbability(
                new float[] {-4f, 0f, 0f, 0f, 0f, 0f, 0f, 0f});

        org.assertj.core.api.Assertions.assertThat(prob)
                .isCloseTo(0.0180, org.assertj.core.data.Offset.offset(1e-3));

        predictor.closeSession();
    }

    @org.junit.jupiter.api.Test
    void predictProbability_wrongFeatureCount_rejected_whenModelLoaded() {
        // Pinned : the validation runs AFTER the isReady() check, so
        // when the model loads the wrong-shape branch becomes reachable.
        // Without this test the validation lived only in the dead-code
        // path of an isReady=false predictor.
        ChurnPredictor predictor = new ChurnPredictor(TEST_ONNX_PATH, "v0-test-fixture");
        predictor.loadModel();

        org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
                .isThrownBy(() -> predictor.predictProbability(new float[] {1f, 2f, 3f}))
                .withMessageContaining("8")
                .withMessageContaining("3");

        predictor.closeSession();
    }

    @org.junit.jupiter.api.Test
    void loadModel_corruptOnnxFile_isCaughtAndIsReadyStaysFalse(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws java.io.IOException {
        // The OrtException | IOException catch in loadModel() was
        // unreachable from earlier tests — both nonexistent-file and
        // happy-load tests sidestep it. A file that EXISTS but is NOT
        // valid ONNX hits the OrtException branch. Pinned because a
        // future refactor that lets the exception propagate would
        // crash the bean and take the whole MCP server down on a
        // subtly broken .onnx artefact (e.g. half-written by a
        // ConfigMap update mid-rollout).
        java.nio.file.Path corrupt = tmp.resolve("corrupt.onnx");
        java.nio.file.Files.writeString(corrupt, "not-a-real-onnx-file");

        ChurnPredictor predictor = new ChurnPredictor(corrupt.toString(), "v0-corrupt");
        predictor.loadModel();

        org.assertj.core.api.Assertions.assertThat(predictor.isReady()).isFalse();
        // closeSession on a never-loaded predictor is a no-op (session is
        // null) — covered indirectly by the isReady() guard inside.
        predictor.closeSession();
    }
}
