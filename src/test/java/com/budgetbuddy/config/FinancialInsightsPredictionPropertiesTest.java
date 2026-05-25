package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the property defaults so they continue to match the original
 * hardcoded constants. If anyone bumps a default without intending to,
 * this test fails before the change ships — defaults govern behaviour
 * for every environment that doesn't override the property.
 */
class FinancialInsightsPredictionPropertiesTest {

    @Test
    void defaults_matchOriginalHardcodedConstants() {
        final FinancialInsightsPredictionProperties p =
                new FinancialInsightsPredictionProperties();
        assertEquals(0.50, p.getMediumConfidence(),
                "Default must match pre-externalization 0.50 — changing this without "
                        + "coordinated alert-volume monitoring will spike or silence alerts.");
        assertEquals(6, p.getMinDataPoints(),
                "Default 6 months matches the pre-externalization minimum.");
        assertEquals(2.0, p.getCategorySpikeMultiplier(),
                "Default 2.0× was the pre-externalization spike threshold.");
        assertEquals(1.5, p.getIqrMultiplier(),
                "Default 1.5× is the classical Tukey IQR upper-fence multiplier.");
    }

    @Test
    void settersOverrideDefaults() {
        final FinancialInsightsPredictionProperties p =
                new FinancialInsightsPredictionProperties();
        p.setMediumConfidence(0.75);
        p.setMinDataPoints(3);
        p.setCategorySpikeMultiplier(3.0);
        p.setIqrMultiplier(2.5);
        assertEquals(0.75, p.getMediumConfidence());
        assertEquals(3, p.getMinDataPoints());
        assertEquals(3.0, p.getCategorySpikeMultiplier());
        assertEquals(2.5, p.getIqrMultiplier());
    }
}
