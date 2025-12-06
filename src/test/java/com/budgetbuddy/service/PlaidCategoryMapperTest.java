package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit Tests for PlaidCategoryMapper
 * Tests Plaid category mapping to internal categories
 */
@ExtendWith(MockitoExtension.class)
class PlaidCategoryMapperTest {

    @InjectMocks
    private PlaidCategoryMapper plaidCategoryMapper;

    @BeforeEach
    void setUp() {
        plaidCategoryMapper = new PlaidCategoryMapper();
    }

    @Test
    void testMapPlaidCategory_WithGroceries_MapsCorrectly() {
        // Given
        String primary = "FOOD_AND_DRINK";
        String detailed = "GROCERIES";
        String merchantName = "Walmart";
        String description = "Grocery shopping";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("groceries", result.getPrimary());
        assertEquals("groceries", result.getDetailed());
        assertFalse(result.isOverridden());
    }

    @Test
    void testMapPlaidCategory_WithRestaurants_MapsToDining() {
        // Given
        String primary = "FOOD_AND_DRINK";
        String detailed = "RESTAURANTS";
        String merchantName = "McDonald's";
        String description = "Fast food";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("dining", result.getPrimary());
        assertEquals("dining", result.getDetailed());
    }

    @Test
    void testMapPlaidCategory_WithTransportation_MapsCorrectly() {
        // Given
        String primary = "TRANSPORTATION";
        String detailed = "GAS_STATIONS";
        String merchantName = "Shell";
        String description = "Gas";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("transportation", result.getPrimary());
        assertEquals("transportation", result.getDetailed());
    }

    @Test
    void testMapPlaidCategory_WithSubscriptions_MapsCorrectly() {
        // Given
        String primary = "ENTERTAINMENT";
        String detailed = "STREAMING_SERVICES";
        String merchantName = "Netflix";
        String description = "Monthly subscription";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("subscriptions", result.getDetailed());
    }

    @Test
    void testMapPlaidCategory_WithEnhancedMerchantDetection_DetectsDining() {
        // Given
        String primary = "GENERAL_MERCHANDISE";
        String detailed = null;
        String merchantName = "Starbucks";
        String description = "Coffee purchase";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("dining", result.getDetailed());
    }

    @Test
    void testMapPlaidCategory_WithEnhancedMerchantDetection_DetectsGroceries() {
        // Given
        String primary = "GENERAL_MERCHANDISE";
        String detailed = null;
        String merchantName = "Target";
        String description = "Shopping";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("groceries", result.getDetailed());
    }

    @Test
    void testMapPlaidCategory_WithEnhancedMerchantDetection_DetectsTransportation() {
        // Given
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "Uber";
        String description = "Ride";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("transportation", result.getDetailed());
    }

    @Test
    void testMapPlaidCategory_WithIncome_MapsCorrectly() {
        // Given
        String primary = "INCOME";
        String detailed = "SALARY";
        String merchantName = "Employer";
        String description = "Payroll";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals("income", result.getDetailed());
    }

    @Test
    void testMapPlaidCategory_WithUnknownCategory_DefaultsToOther() {
        // Given
        String primary = "UNKNOWN_CATEGORY";
        String detailed = null;
        String merchantName = null;
        String description = null;

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("other", result.getPrimary());
        assertEquals("other", result.getDetailed());
    }

    @Test
    void testMapPlaidCategory_WithNullInputs_HandlesGracefully() {
        // Given
        String primary = null;
        String detailed = null;
        String merchantName = null;
        String description = null;

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("UNKNOWN_CATEGORY", result.getPrimary());
        assertEquals("UNKNOWN_CATEGORY", result.getDetailed());
    }

    @Test
    void testApplyOverride_WithValidOverride_ReturnsOverriddenMapping() {
        // Given
        PlaidCategoryMapper.CategoryMapping original = new PlaidCategoryMapper.CategoryMapping(
                "dining", "restaurants", false);
        String overridePrimary = "groceries";
        String overrideDetailed = "supermarket";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.applyOverride(
                original, overridePrimary, overrideDetailed);

        // Then
        assertNotNull(result);
        assertEquals("groceries", result.getPrimary());
        assertEquals("supermarket", result.getDetailed());
        assertTrue(result.isOverridden());
    }

    @Test
    void testApplyOverride_WithPartialOverride_UsesOriginalForMissing() {
        // Given
        PlaidCategoryMapper.CategoryMapping original = new PlaidCategoryMapper.CategoryMapping(
                "dining", "restaurants", false);
        String overridePrimary = "groceries";
        String overrideDetailed = null; // No detailed override

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.applyOverride(
                original, overridePrimary, overrideDetailed);

        // Then
        assertNotNull(result);
        assertEquals("groceries", result.getPrimary());
        assertEquals("restaurants", result.getDetailed()); // Uses original
        assertTrue(result.isOverridden());
    }

    @Test
    void testApplyOverride_WithEmptyOverride_UsesOriginal() {
        // Given
        PlaidCategoryMapper.CategoryMapping original = new PlaidCategoryMapper.CategoryMapping(
                "dining", "restaurants", false);
        String overridePrimary = "";
        String overrideDetailed = "";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.applyOverride(
                original, overridePrimary, overrideDetailed);

        // Then
        assertNotNull(result);
        assertEquals("dining", result.getPrimary());
        assertEquals("restaurants", result.getDetailed());
        assertTrue(result.isOverridden());
    }
}

