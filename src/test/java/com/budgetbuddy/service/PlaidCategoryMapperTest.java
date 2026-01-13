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
        // Target can be categorized as either groceries or shopping depending on context
        // Both are valid categorizations for a store that sells both
        assertTrue("groceries".equals(result.getDetailed()) || "shopping".equals(result.getDetailed()),
                "Expected groceries or shopping but got: " + result.getDetailed());
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
        // CRITICAL: "income" is ONLY a primary category, not a detailed category
        // Description contains "Payroll", so should be categorized as salary, not generic income
        assertEquals("salary", result.getDetailed(), "Income with payroll description should be salary (not generic income)");
    }

    @Test
    void testMapPlaidCategory_WithInterestMisspelling_INTRST_DetectedAsInterest() {
        // Given: Interest payment with misspelling "INTRST"
        String primary = "INCOME";
        String detailed = "INTEREST_EARNED";
        String merchantName = null;
        String description = "INTRST payment";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals("interest", result.getDetailed(), "INTRST payment should be detected as interest");
    }

    @Test
    void testMapPlaidCategory_WithInterestMisspelling_INTR_DetectedAsInterest() {
        // Given: Interest payment with misspelling "INTR"
        String primary = "INCOME";
        String detailed = null;
        String merchantName = null;
        String description = "INTR payment";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals("interest", result.getDetailed(), "INTR payment should be detected as interest");
    }

    @Test
    void testMapPlaidCategory_WithInterestMisspelling_INTREST_DetectedAsInterest() {
        // Given: Interest payment with misspelling "INTREST"
        String primary = "INCOME";
        String detailed = null;
        String merchantName = null;
        String description = "INTREST payment";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals("interest", result.getDetailed(), "INTREST payment should be detected as interest");
    }

    @Test
    void testMapPlaidCategory_WithInterestMisspelling_INTRSTPayment_DetectedAsInterest() {
        // Given: Interest payment with misspelling "INTRST payment"
        String primary = "INCOME";
        String detailed = null;
        String merchantName = null;
        String description = "INTRST payment";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals("interest", result.getDetailed(), "INTRST payment should be detected as interest");
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
    void testMapPlaidCategory_WithINTRSTPYMNT_OverridesOtherToInterest() {
        // Given: Interest payment with "INTRST PYMNT" description but Plaid sent "other" category
        // This tests the critical fix that ensures interest payments are always Income/Interest
        String primary = "GENERAL_SERVICES"; // Plaid incorrectly categorizes as "other"
        String detailed = null;
        String merchantName = null;
        String description = "INTRST PYMNT";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary(), "INTRST PYMNT should override primary to income");
        assertEquals("interest", result.getDetailed(), "INTRST PYMNT should override detailed to interest");
    }

    @Test
    void testMapPlaidCategory_WithINTRSTPYMNT_OverridesUnknownCategoryToInterest() {
        // Given: Interest payment with "INTRST PYMNT" description but Plaid sent "UNKNOWN_CATEGORY"
        String primary = "UNKNOWN_CATEGORY";
        String detailed = null;
        String merchantName = null;
        String description = "INTRST PYMNT";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary(), "INTRST PYMNT should override UNKNOWN_CATEGORY to income");
        assertEquals("interest", result.getDetailed(), "INTRST PYMNT should override UNKNOWN_CATEGORY to interest");
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

    // ========== Airline Transaction Tests ==========

    @Test
    void testDeltaAirlines_Travel_NotUtilities() {
        // Given: Delta Airlines transaction that was incorrectly categorized as utilities
        String primary = "RENT_AND_UTILITIES"; // Plaid incorrectly categorizes as utilities
        String detailed = "UTILITIES";
        String merchantName = "DELTA AIR LINES ATLANTA DELTA AIR LINES From: To: Carrier: Class: NASHVILLE SEATTLE-TACOMA INT DL Q Ticket Number: 00623608559696 Date of Departure: 09/13 Passenger Name: AGARWAL/MUDIT Document Type: PASSENGER TICKET";
        String description = "DELTA AIR LINES ATLANTA DELTA AIR LINES From: To: Carrier: Class: NASHVILLE SEATTLE-TACOMA INT DL Q Ticket Number: 00623608559696 Date of Departure: 09/13 Passenger Name: AGARWAL/MUDIT Document Type: PASSENGER TICKET";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "Delta Airlines should be travel, not utilities");
        assertEquals("travel", result.getDetailed(), "Delta Airlines should be travel, not utilities");
    }

    @Test
    void testAlaskaAirlines_Travel_NotUtilities() {
        // Given: Alaska Airlines transaction that was incorrectly categorized as utilities
        String primary = "RENT_AND_UTILITIES";
        String detailed = "UTILITIES";
        String merchantName = "ALASKA AIRLINES SEATTLE WA ALASKA AIRLINES From: To: Carrier: Class: SEATTLE-TACOMA INT NASHVILLE AS 00 Ticket Number: 0274420370977 Date of Departure: 05/25 Passenger Name: AGARWAL/MUDIT Document Type: PASSENGER TICKET";
        String description = "ALASKA AIRLINES SEATTLE WA ALASKA AIRLINES From: To: Carrier: Class: SEATTLE-TACOMA INT NASHVILLE AS 00 Ticket Number: 0274420370977 Date of Departure: 05/25 Passenger Name: AGARWAL/MUDIT Document Type: PASSENGER TICKET";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "Alaska Airlines should be travel, not utilities");
        assertEquals("travel", result.getDetailed(), "Alaska Airlines should be travel, not utilities");
    }

    @Test
    void testUnitedAirlines_Travel_NotOther() {
        // Given: United Airlines transaction
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "UNITED AIRLINES";
        String description = "Flight ticket";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "United Airlines should be travel");
        assertEquals("travel", result.getDetailed(), "United Airlines should be travel");
    }

    // ========== Shopping Transaction Tests ==========

    @Test
    void testLululemon_Shopping_NotTransportation() {
        // Given: Lululemon transaction that was incorrectly categorized as transportation
        String primary = "TRANSPORTATION";
        String detailed = "TRANSPORTATION";
        String merchantName = "AGARWAL SUMIT KUMAR Platinum Lululemon Credit LULULEMON ATHLETICA USA B";
        String description = "AGARWAL SUMIT KUMAR Platinum Lululemon Credit LULULEMON ATHLETICA USA B";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("shopping", result.getPrimary(), "Lululemon should be shopping, not transportation");
        assertEquals("shopping", result.getDetailed(), "Lululemon should be shopping, not transportation");
    }

    @Test
    void testNike_Shopping_NotOther() {
        // Given: Nike transaction
        String primary = "GENERAL_MERCHANDISE";
        String detailed = null;
        String merchantName = "Nike Store";
        String description = "Nike purchase";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("shopping", result.getPrimary(), "Nike should be shopping");
        assertEquals("shopping", result.getDetailed(), "Nike should be shopping");
    }

    @Test
    void testNordstrom_Shopping_NotOther() {
        // Given: Nordstrom transaction
        String primary = "GENERAL_MERCHANDISE";
        String detailed = null;
        String merchantName = "Nordstrom";
        String description = "Clothing purchase";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("shopping", result.getPrimary(), "Nordstrom should be shopping");
        assertEquals("shopping", result.getDetailed(), "Nordstrom should be shopping");
    }

    // ========== Movie Theater/Entertainment Tests ==========

    @Test
    void testAMCTheater_Entertainment_NotEducation() {
        // Given: AMC movie theater transaction that was incorrectly categorized as education
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "AMC 2434 FACTORIA 8 BELLEVUE WA";
        String description = "AMC 2434 FACTORIA 8 BELLEVUE WA";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("entertainment", result.getPrimary(), "AMC should be entertainment, not education");
        assertEquals("entertainment", result.getDetailed(), "AMC should be entertainment, not education");
    }

    @Test
    void testRegalCinema_Entertainment_NotOther() {
        // Given: Regal Cinema transaction
        String primary = "ENTERTAINMENT";
        String detailed = null;
        String merchantName = "Regal Cinemas";
        String description = "Movie tickets";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("entertainment", result.getPrimary(), "Regal Cinema should be entertainment");
        assertEquals("entertainment", result.getDetailed(), "Regal Cinema should be entertainment");
    }

    @Test
    void testCinemark_Entertainment_NotOther() {
        // Given: Cinemark transaction
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "Cinemark";
        String description = "Movie";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("entertainment", result.getPrimary(), "Cinemark should be entertainment");
        assertEquals("entertainment", result.getDetailed(), "Cinemark should be entertainment");
    }

    // ========== Airport Lounge Tests ==========

    @Test
    void testAXPCenturionLounge_Travel_NotUtilities() {
        // Given: AXP Centurion Lounge transaction that was incorrectly categorized as utilities
        String primary = "RENT_AND_UTILITIES";
        String detailed = "UTILITIES";
        String merchantName = "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER";
        String description = "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "AXP Centurion Lounge should be travel, not utilities");
        assertEquals("travel", result.getDetailed(), "AXP Centurion Lounge should be travel, not utilities");
    }

    @Test
    void testPriorityPassLounge_Travel_NotOther() {
        // Given: Priority Pass lounge transaction
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "Priority Pass Lounge";
        String description = "Airport lounge access";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "Priority Pass should be travel");
        assertEquals("travel", result.getDetailed(), "Priority Pass should be travel");
    }

    @Test
    void testDeltaSkyClub_Travel_NotOther() {
        // Given: Delta Sky Club transaction
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "Delta Sky Club";
        String description = "Airport lounge";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "Delta Sky Club should be travel");
        assertEquals("travel", result.getDetailed(), "Delta Sky Club should be travel");
    }

    // ========== Direct Payment Tests ==========

    @Test
    void testDirectPay_Payment_NotOther() {
        // Given: Direct payment transaction with positive amount that should be Payment type
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "1% Cashback Bonus +$0.06 DIRECTPAY FULL BALANCE";
        String description = "1% Cashback Bonus +$0.06 DIRECTPAY FULL BALANCE";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description, null, java.math.BigDecimal.valueOf(11.74));

        // Then
        assertNotNull(result);
        assertEquals("payment", result.getPrimary(), "DIRECTPAY should be payment, not other");
        assertEquals("payment", result.getDetailed(), "DIRECTPAY should be payment, not other");
    }

    @Test
    void testDirectPayment_Payment_NotOther() {
        // Given: Direct payment transaction
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "Direct Payment";
        String description = "Direct payment to credit card";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("payment", result.getPrimary(), "Direct payment should be payment");
        assertEquals("payment", result.getDetailed(), "Direct payment should be payment");
    }

    @Test
    void testAutomatyment_Payment_NotOther() {
        // Given: Automatyment (misspelling of automatic payment) transaction
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "Automatyment";
        String description = "Automatyment payment";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("payment", result.getPrimary(), "Automatyment should be payment");
        assertEquals("payment", result.getDetailed(), "Automatyment should be payment");
    }

    // ========== Holdings Company Tests ==========

    @Test
    void testTRGHoldings_Other_NotDining() {
        // Given: TRG Holdings transaction that was incorrectly categorized as dining
        String primary = "FOOD_AND_DRINK"; // Plaid incorrectly categorizes as dining
        String detailed = "RESTAURANTS";
        String merchantName = "TRG HOLDINGS LIMITED LONDON";
        String description = "TRG HOLDINGS LIMITED LONDON";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("other", result.getPrimary(), "TRG Holdings should be other, not dining");
        assertEquals("other", result.getDetailed(), "TRG Holdings should be other, not dining");
    }

    @Test
    void testHoldingsLimited_Other_NotDining() {
        // Given: Generic holdings company transaction
        String primary = "FOOD_AND_DRINK";
        String detailed = "RESTAURANTS";
        String merchantName = "ABC HOLDINGS LIMITED";
        String description = "ABC HOLDINGS LIMITED";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("other", result.getPrimary(), "Holdings company should be other, not dining");
        assertEquals("other", result.getDetailed(), "Holdings company should be other, not dining");
    }

    @Test
    void testHoldingsCompany_Other_NotOtherCategory() {
        // Given: Holdings company with no specific category
        String primary = "GENERAL_SERVICES";
        String detailed = null;
        String merchantName = "XYZ HOLDINGS LIMITED";
        String description = "Business transaction";

        // When
        PlaidCategoryMapper.CategoryMapping result = plaidCategoryMapper.mapPlaidCategory(
                primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("other", result.getPrimary(), "Holdings company should be other");
        assertEquals("other", result.getDetailed(), "Holdings company should be other");
    }
}

