package com.budgetbuddy.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Unit Tests for FeatureFlagConfig Tests feature flag configuration */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(
        properties = {
            "app.features.enable-plaid=true",
            "app.features.enable-stripe=true",
            "app.features.enable-oauth2=false",
            "app.features.enable-advanced-analytics=false",
            "app.features.enable-notifications=true",
            "app.aws.dynamodb.table-prefix=TestBudgetBuddy"
        })
class FeatureFlagConfigTest {

    @Autowired private FeatureFlagConfig.FeatureFlags featureFlags;

    @Test
    void testFeatureFlagsWithEnabledFeaturesReturnsTrue() {
        // When/Then
        assertTrue(featureFlags.isEnabled("plaid"));
        assertTrue(featureFlags.isEnabled("stripe"));
        assertTrue(featureFlags.isEnabled("notifications"));
    }

    @Test
    void testFeatureFlagsWithDisabledFeaturesReturnsFalse() {
        // When/Then
        assertFalse(featureFlags.isEnabled("oauth2"));
        assertFalse(featureFlags.isEnabled("advanced-analytics"));
    }

    @Test
    void testFeatureFlagsWithUnknownFeatureReturnsFalse() {
        // When/Then
        assertFalse(featureFlags.isEnabled("unknown-feature"));
    }

    @Test
    void testGetAllFlagsReturnsAllFlags() {
        // When
        final var allFlags = featureFlags.getAllFlags();

        // Then
        assertNotNull(allFlags);
        assertTrue(allFlags.containsKey("plaid"));
        assertTrue(allFlags.containsKey("stripe"));
    }
}
