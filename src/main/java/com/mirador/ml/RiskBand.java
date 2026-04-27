package com.mirador.ml;

/**
 * Coarse-grained churn risk classification, derived from the
 * {@code probability} returned by {@link ChurnPredictor}.
 *
 * <p>Three bands map naturally to UI affordances :
 * <ul>
 *   <li>{@link #LOW}    — probability ≤ 0.3 ; green dot, no action.
 *   <li>{@link #MEDIUM} — probability ∈ (0.3, 0.7] ; orange dot, watch list.
 *   <li>{@link #HIGH}   — probability &gt; 0.7 ; red dot, retention email.
 * </ul>
 *
 * <p>The thresholds are tunable via {@code mirador.churn.risk-thresholds}
 * Spring property so the business can re-calibrate without code change.
 */
public enum RiskBand {
    LOW,
    MEDIUM,
    HIGH;

    /** Default LOW threshold — probability strictly below = LOW. */
    public static final double DEFAULT_LOW_THRESHOLD = 0.3;
    /** Default HIGH threshold — probability strictly above = HIGH. */
    public static final double DEFAULT_HIGH_THRESHOLD = 0.7;

    /**
     * Classify a probability into one of the three bands using the
     * default thresholds.
     */
    public static RiskBand classify(double probability) {
        return classify(probability, DEFAULT_LOW_THRESHOLD, DEFAULT_HIGH_THRESHOLD);
    }

    /**
     * Classify a probability using caller-supplied thresholds. Validates
     * {@code low ≤ high} but leaves the caller to enforce the [0,1]
     * range on the probability itself.
     */
    public static RiskBand classify(double probability, double lowThreshold, double highThreshold) {
        if (lowThreshold > highThreshold) {
            throw new IllegalArgumentException(
                    "low threshold " + lowThreshold + " must be ≤ high threshold " + highThreshold);
        }
        if (probability <= lowThreshold) {
            return LOW;
        }
        if (probability > highThreshold) {
            return HIGH;
        }
        return MEDIUM;
    }
}
