
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
 * Tests for FeatureFlagConfig
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestPropertySource(properties = {
    "app.features.enable-plaid=true",
    "app.features.enable-stripe=true",
    "app.features.enable-oauth2=false",
    "app.features.enable-advanced-analytics=false",
    "app.features.enable-notifications=true"
})
class FeatureFlagConfigTest {

    @Autowired
    private FeatureFlagConfig.FeatureFlags featureFlags;

    @Test
    void testFeatureFlags_IsEnabled_WithEnabledFeature_ReturnsTrue() {
        // Given - Plaid is enabled
        // When
        boolean enabled = featureFlags.isEnabled("plaid");

        // Then
        assertTrue(enabled, "Plaid feature should be enabled");
    }

    @Test
    void testFeatureFlags_IsEnabled_WithDisabledFeature_ReturnsFalse() {
        // Given - OAuth2 is disabled
        // When
        boolean enabled = featureFlags.isEnabled("oauth2");

        // Then
        assertFalse(enabled, "OAuth2 feature should be disabled");
    }

    @Test
    void testFeatureFlags_IsEnabled_WithUnknownFeature_ReturnsFalse() {
        // Given - Unknown feature
        // When
        boolean enabled = featureFlags.isEnabled("unknown-feature");

        // Then
        assertFalse(enabled, "Unknown feature should return false");
    }

    @Test
    void testFeatureFlags_IsEnabled_WithNullFeature_ReturnsFalse() {
        // Given - Null feature name
        // When
        boolean enabled = featureFlags.isEnabled(null);

        // Then
        assertFalse(enabled, "Null feature should return false");
    }

    @Test
    void testFeatureFlags_GetAllFlags_ReturnsAllFlags() {
        // When
        var allFlags = featureFlags.getAllFlags();

        // Then
        assertNotNull(allFlags, "Should return flags map");
        assertTrue(allFlags.containsKey("plaid"), "Should contain plaid flag");
        assertTrue(allFlags.containsKey("stripe"), "Should contain stripe flag");
        assertTrue(allFlags.containsKey("oauth2"), "Should contain oauth2 flag");
        assertTrue(allFlags.containsKey("advanced-analytics"), "Should contain advanced-analytics flag");
        assertTrue(allFlags.containsKey("notifications"), "Should contain notifications flag");
    }

    @Test
    void testFeatureFlags_GetAllFlags_ReturnsCopy() {
        // Given - Get flags and modify
        var flags1 = featureFlags.getAllFlags();
        flags1.put("new-flag", true);

        // When - Get flags again
        var flags2 = featureFlags.getAllFlags();

        // Then - Original should not be modified
        assertFalse(flags2.containsKey("new-flag"), "Modifying returned map should not affect original");
    }

    @Test
    void testFeatureFlags_WithAllFeaturesEnabled() {
        // Given - All features enabled via properties
        // When
        boolean plaidEnabled = featureFlags.isEnabled("plaid");
        boolean stripeEnabled = featureFlags.isEnabled("stripe");
        boolean notificationsEnabled = featureFlags.isEnabled("notifications");

        // Then
        assertTrue(plaidEnabled, "Plaid should be enabled");
        assertTrue(stripeEnabled, "Stripe should be enabled");
        assertTrue(notificationsEnabled, "Notifications should be enabled");
    }

    @Test
    void testFeatureFlags_WithAllFeaturesDisabled() {
        // Given - Features disabled via properties
        // When
        boolean oauth2Enabled = featureFlags.isEnabled("oauth2");
        boolean analyticsEnabled = featureFlags.isEnabled("advanced-analytics");

        // Then
        assertFalse(oauth2Enabled, "OAuth2 should be disabled");
        assertFalse(analyticsEnabled, "Advanced analytics should be disabled");
    }
}

