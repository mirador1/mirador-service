package com.mirador.ml;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for the {@link RiskBand} classification logic.
 *
 * <p>The bands map LOW / MEDIUM / HIGH probabilities to the UI affordance
 * (green / orange / red dot). Edge cases at the threshold boundaries are
 * the load-bearing contract — a probability of exactly 0.3 must be LOW
 * (≤ inclusive on the low side), 0.7 must be MEDIUM (≤ inclusive on the
 * high side, &gt; exclusive).
 */
class RiskBandTest {

    @Test
    void classifyAtLowBoundaryReturnsLow() {
        assertThat(RiskBand.classify(0.0)).isEqualTo(RiskBand.LOW);
        assertThat(RiskBand.classify(0.3)).isEqualTo(RiskBand.LOW);
    }

    @Test
    void classifyAboveLowBoundaryReturnsMedium() {
        assertThat(RiskBand.classify(0.31)).isEqualTo(RiskBand.MEDIUM);
        assertThat(RiskBand.classify(0.5)).isEqualTo(RiskBand.MEDIUM);
        assertThat(RiskBand.classify(0.7)).isEqualTo(RiskBand.MEDIUM);
    }

    @Test
    void classifyAboveHighBoundaryReturnsHigh() {
        assertThat(RiskBand.classify(0.71)).isEqualTo(RiskBand.HIGH);
        assertThat(RiskBand.classify(0.99)).isEqualTo(RiskBand.HIGH);
        assertThat(RiskBand.classify(1.0)).isEqualTo(RiskBand.HIGH);
    }

    @Test
    void classifyAcceptsCustomThresholds() {
        // Tight 0.5 / 0.6 thresholds for a stricter trigger.
        assertThat(RiskBand.classify(0.5, 0.5, 0.6)).isEqualTo(RiskBand.LOW);
        assertThat(RiskBand.classify(0.55, 0.5, 0.6)).isEqualTo(RiskBand.MEDIUM);
        assertThat(RiskBand.classify(0.65, 0.5, 0.6)).isEqualTo(RiskBand.HIGH);
    }

    @Test
    void classifyRejectsInvertedThresholds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> RiskBand.classify(0.5, 0.7, 0.3))
                .withMessageContaining("must be ≤");
    }
}
