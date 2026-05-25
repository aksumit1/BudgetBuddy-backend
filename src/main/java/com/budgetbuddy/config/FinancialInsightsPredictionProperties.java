package com.budgetbuddy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Tunable statistical thresholds for
 * {@code FinancialInsightsPredictionService}. Externalized so ops can
 * raise/lower the noise floor per environment without recompiling —
 * production frequently needs different values than staging.
 *
 * <p>Defaults preserve the pre-externalization behaviour exactly so this
 * change is a no-op until the operator chooses to tune. Properties bind
 * under {@code financial.insights.prediction.*}.
 */
@Configuration
@ConfigurationProperties(prefix = "financial.insights.prediction")
public class FinancialInsightsPredictionProperties {

    /**
     * R² threshold above which a regression's slope counts as
     * "significant enough to act on". Was hardcoded 0.50. Raise for
     * fewer / higher-quality predictions, lower for more / noisier.
     */
    private double mediumConfidence = 0.50;

    /**
     * Minimum observations required to fit a trend. Was hardcoded 6
     * (months). Raise for stricter signal-quality requirements; lower
     * to surface predictions sooner for new users.
     */
    private int minDataPoints = 6;

    /**
     * Multiplier vs. historical average above which a predicted amount
     * counts as a "spike worth alerting on". Was hardcoded 2.0×.
     */
    private double categorySpikeMultiplier = 2.0;

    /**
     * IQR multiplier defining the upper-fence threshold for amount
     * anomalies. Was hardcoded 1.5×, the classical Tukey value.
     */
    private double iqrMultiplier = 1.5;

    public double getMediumConfidence() {
        return mediumConfidence;
    }

    public void setMediumConfidence(final double mediumConfidence) {
        this.mediumConfidence = mediumConfidence;
    }

    public int getMinDataPoints() {
        return minDataPoints;
    }

    public void setMinDataPoints(final int minDataPoints) {
        this.minDataPoints = minDataPoints;
    }

    public double getCategorySpikeMultiplier() {
        return categorySpikeMultiplier;
    }

    public void setCategorySpikeMultiplier(final double categorySpikeMultiplier) {
        this.categorySpikeMultiplier = categorySpikeMultiplier;
    }

    public double getIqrMultiplier() {
        return iqrMultiplier;
    }

    public void setIqrMultiplier(final double iqrMultiplier) {
        this.iqrMultiplier = iqrMultiplier;
    }
}
