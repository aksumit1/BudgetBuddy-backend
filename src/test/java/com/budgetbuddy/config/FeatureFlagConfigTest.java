package com.budgetbuddy.config;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for FeatureFlagConfig
 * Tests feature flag configuration
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = {
    "app.features.enable-plaid=true",
    "app.features.enable-stripe=true",
    "app.features.enable-oauth2=false",
    "app.features.enable-advanced-analytics=false",
    "app.features.enable-notifications=true",
    "app.aws.dynamodb.table-prefix=TestBudgetBuddy"
})
class FeatureFlagConfigTest {

    @Autowired
    private FeatureFlagConfig.FeatureFlags featureFlags;

    @Test
    void testFeatureFlags_WithEnabledFeatures_ReturnsTrue() {
        // When/Then
        assertTrue(featureFlags.isEnabled("plaid"));
        assertTrue(featureFlags.isEnabled("stripe"));
        assertTrue(featureFlags.isEnabled("notifications"));
    }

    @Test
    void testFeatureFlags_WithDisabledFeatures_ReturnsFalse() {
        // When/Then
        assertFalse(featureFlags.isEnabled("oauth2"));
        assertFalse(featureFlags.isEnabled("advanced-analytics"));
    }

    @Test
    void testFeatureFlags_WithUnknownFeature_ReturnsFalse() {
        // When/Then
        assertFalse(featureFlags.isEnabled("unknown-feature"));
    }

    @Test
    void testGetAllFlags_ReturnsAllFlags() {
        // When
        var allFlags = featureFlags.getAllFlags();

        // Then
        assertNotNull(allFlags);
        assertTrue(allFlags.containsKey("plaid"));
        assertTrue(allFlags.containsKey("stripe"));
    }
}
