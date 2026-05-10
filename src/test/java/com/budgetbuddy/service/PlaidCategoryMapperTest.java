package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for PlaidCategoryMapper Tests Plaid category mapping to internal categories */
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
@ExtendWith(MockitoExtension.class)
class PlaidCategoryMapperTest {

    @InjectMocks private PlaidCategoryMapper plaidCategoryMapper;

    @BeforeEach
    void setUp() {
        plaidCategoryMapper = new PlaidCategoryMapper();
    }

    @Test
    void testMapPlaidCategoryWithGroceriesMapsCorrectly() {
        // Given
        final String primary = "FOOD_AND_DRINK";
        final String detailed = "GROCERIES";
        final String merchantName = "Walmart";
        final String description = "Grocery shopping";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("groceries", result.getPrimary());
        assertEquals("groceries", result.getDetailed());
        assertFalse(result.isOverridden());
    }

    @Test
    void testMapPlaidCategoryWithRestaurantsMapsToDining() {
        // Given
        final String primary = "FOOD_AND_DRINK";
        final String detailed = "RESTAURANTS";
        final String merchantName = "McDonald's";
        final String description = "Fast food";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("dining", result.getPrimary());
        assertEquals("dining", result.getDetailed());
    }

    @Test
    void testMapPlaidCategoryWithTransportationMapsCorrectly() {
        // Given
        final String primary = "TRANSPORTATION";
        final String detailed = "GAS_STATIONS";
        final String merchantName = "Shell";
        final String description = "Gas";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("transportation", result.getPrimary());
        assertEquals("transportation", result.getDetailed());
    }

    @Test
    void testMapPlaidCategoryWithSubscriptionsMapsCorrectly() {
        // Given
        final String primary = "ENTERTAINMENT";
        final String detailed = "STREAMING_SERVICES";
        final String merchantName = "Netflix";
        final String description = "Monthly subscription";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("subscriptions", result.getDetailed());
    }

    @Test
    void testMapPlaidCategoryWithEnhancedMerchantDetectionDetectsDining() {
        // Given
        final String primary = "GENERAL_MERCHANDISE";
        final String detailed = null;
        final String merchantName = "Starbucks";
        final String description = "Coffee purchase";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("dining", result.getDetailed());
    }

    @Test
    void testMapPlaidCategoryWithEnhancedMerchantDetectionDetectsGroceries() {
        // Given
        final String primary = "GENERAL_MERCHANDISE";
        final String detailed = null;
        final String merchantName = "Target";
        final String description = "Shopping";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        // Target can be categorized as either groceries or shopping depending on context
        // Both are valid categorizations for a store that sells both
        assertTrue(
                "groceries".equals(result.getDetailed()) || "shopping".equals(result.getDetailed()),
                "Expected groceries or shopping but got: " + result.getDetailed());
    }

    @Test
    void testMapPlaidCategoryWithEnhancedMerchantDetectionDetectsTransportation() {
        // Given
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "Uber";
        final String description = "Ride";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("transportation", result.getDetailed());
    }

    @Test
    void testMapPlaidCategoryWithIncomeMapsCorrectly() {
        // Given
        final String primary = "INCOME";
        final String detailed = "SALARY";
        final String merchantName = "Employer";
        final String description = "Payroll";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        // CRITICAL: "income" is ONLY a primary category, not a detailed category
        // Description contains "Payroll", so should be categorized as salary, not generic income
        assertEquals(
                "salary",
                result.getDetailed(),
                "Income with payroll description should be salary (not generic income)");
    }

    @Test
    void testMapPlaidCategoryWithInterestMisspellingINTRSTDetectedAsInterest() {
        // Given: Interest payment with misspelling "INTRST"
        final String primary = "INCOME";
        final String detailed = "INTEREST_EARNED";
        final String merchantName = null;
        final String description = "INTRST payment";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals(
                "interest", result.getDetailed(), "INTRST payment should be detected as interest");
    }

    @Test
    void testMapPlaidCategoryWithInterestMisspellingINTRDetectedAsInterest() {
        // Given: Interest payment with misspelling "INTR"
        final String primary = "INCOME";
        final String detailed = null;
        final String merchantName = null;
        final String description = "INTR payment";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals(
                "interest", result.getDetailed(), "INTR payment should be detected as interest");
    }

    @Test
    void testMapPlaidCategoryWithInterestMisspellingINTRESTDetectedAsInterest() {
        // Given: Interest payment with misspelling "INTREST"
        final String primary = "INCOME";
        final String detailed = null;
        final String merchantName = null;
        final String description = "INTREST payment";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals(
                "interest", result.getDetailed(), "INTREST payment should be detected as interest");
    }

    @Test
    void testMapPlaidCategoryWithInterestMisspellingINTRSTPaymentDetectedAsInterest() {
        // Given: Interest payment with misspelling "INTRST payment"
        final String primary = "INCOME";
        final String detailed = null;
        final String merchantName = null;
        final String description = "INTRST payment";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("income", result.getPrimary());
        assertEquals(
                "interest", result.getDetailed(), "INTRST payment should be detected as interest");
    }

    @Test
    void testMapPlaidCategoryWithUnknownCategoryDefaultsToOther() {
        // Given
        final String primary = "UNKNOWN_CATEGORY";
        final String detailed = null;
        final String merchantName = null;
        final String description = null;

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("other", result.getPrimary());
        assertEquals("other", result.getDetailed());
    }

    @Test
    void testMapPlaidCategoryWithINTRSTPYMNTOverridesOtherToInterest() {
        // Given: Interest payment with "INTRST PYMNT" description but Plaid sent "other" category
        // This tests the critical fix that ensures interest payments are always Income/Interest
        final String primary = "GENERAL_SERVICES"; // Plaid incorrectly categorizes as "other"
        final String detailed = null;
        final String merchantName = null;
        final String description = "INTRST PYMNT";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals(
                "income", result.getPrimary(), "INTRST PYMNT should override primary to income");
        assertEquals(
                "interest",
                result.getDetailed(),
                "INTRST PYMNT should override detailed to interest");
    }

    @Test
    void testMapPlaidCategoryWithINTRSTPYMNTOverridesUnknownCategoryToInterest() {
        // Given: Interest payment with "INTRST PYMNT" description but Plaid sent "UNKNOWN_CATEGORY"
        final String primary = "UNKNOWN_CATEGORY";
        final String detailed = null;
        final String merchantName = null;
        final String description = "INTRST PYMNT";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals(
                "income",
                result.getPrimary(),
                "INTRST PYMNT should override UNKNOWN_CATEGORY to income");
        assertEquals(
                "interest",
                result.getDetailed(),
                "INTRST PYMNT should override UNKNOWN_CATEGORY to interest");
    }

    @Test
    void testMapPlaidCategoryWithNullInputsHandlesGracefully() {
        // Given
        final String primary = null;
        final String detailed = null;
        final String merchantName = null;
        final String description = null;

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("UNKNOWN_CATEGORY", result.getPrimary());
        assertEquals("UNKNOWN_CATEGORY", result.getDetailed());
    }

    @Test
    void testApplyOverrideWithValidOverrideReturnsOverriddenMapping() {
        // Given
        final PlaidCategoryMapper.CategoryMapping original =
                new PlaidCategoryMapper.CategoryMapping("dining", "restaurants", false);
        final String overridePrimary = "groceries";
        final String overrideDetailed = "supermarket";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.applyOverride(original, overridePrimary, overrideDetailed);

        // Then
        assertNotNull(result);
        assertEquals("groceries", result.getPrimary());
        assertEquals("supermarket", result.getDetailed());
        assertTrue(result.isOverridden());
    }

    @Test
    void testApplyOverrideWithPartialOverrideUsesOriginalForMissing() {
        // Given
        final PlaidCategoryMapper.CategoryMapping original =
                new PlaidCategoryMapper.CategoryMapping("dining", "restaurants", false);
        final String overridePrimary = "groceries";
        final String overrideDetailed = null; // No detailed override

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.applyOverride(original, overridePrimary, overrideDetailed);

        // Then
        assertNotNull(result);
        assertEquals("groceries", result.getPrimary());
        assertEquals("restaurants", result.getDetailed()); // Uses original
        assertTrue(result.isOverridden());
    }

    @Test
    void testApplyOverrideWithEmptyOverrideUsesOriginal() {
        // Given
        final PlaidCategoryMapper.CategoryMapping original =
                new PlaidCategoryMapper.CategoryMapping("dining", "restaurants", false);
        final String overridePrimary = "";
        final String overrideDetailed = "";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.applyOverride(original, overridePrimary, overrideDetailed);

        // Then
        assertNotNull(result);
        assertEquals("dining", result.getPrimary());
        assertEquals("restaurants", result.getDetailed());
        assertTrue(result.isOverridden());
    }

    // ========== Airline Transaction Tests ==========

    @Test
    void testDeltaAirlinesTravelNotUtilities() {
        // Given: Delta Airlines transaction that was incorrectly categorized as utilities
        final String primary = "RENT_AND_UTILITIES"; // Plaid incorrectly categorizes as utilities
        final String detailed = "UTILITIES";
        final String merchantName =
                "DELTA AIR LINES ATLANTA DELTA AIR LINES From: To: Carrier: Class: NASHVILLE SEATTLE-TACOMA INT DL Q Ticket Number: 00623608559696 Date of Departure: 09/13 Passenger Name: AGARWAL/MUDIT Document Type: PASSENGER TICKET";
        final String description =
                "DELTA AIR LINES ATLANTA DELTA AIR LINES From: To: Carrier: Class: NASHVILLE SEATTLE-TACOMA INT DL Q Ticket Number: 00623608559696 Date of Departure: 09/13 Passenger Name: AGARWAL/MUDIT Document Type: PASSENGER TICKET";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals(
                "travel", result.getPrimary(), "Delta Airlines should be travel, not utilities");
        assertEquals(
                "travel", result.getDetailed(), "Delta Airlines should be travel, not utilities");
    }

    @Test
    void testAlaskaAirlinesTravelNotUtilities() {
        // Given: Alaska Airlines transaction that was incorrectly categorized as utilities
        final String primary = "RENT_AND_UTILITIES";
        final String detailed = "UTILITIES";
        final String merchantName =
                "ALASKA AIRLINES SEATTLE WA ALASKA AIRLINES From: To: Carrier: Class: SEATTLE-TACOMA INT NASHVILLE AS 00 Ticket Number: 0274420370977 Date of Departure: 05/25 Passenger Name: AGARWAL/MUDIT Document Type: PASSENGER TICKET";
        final String description =
                "ALASKA AIRLINES SEATTLE WA ALASKA AIRLINES From: To: Carrier: Class: SEATTLE-TACOMA INT NASHVILLE AS 00 Ticket Number: 0274420370977 Date of Departure: 05/25 Passenger Name: AGARWAL/MUDIT Document Type: PASSENGER TICKET";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals(
                "travel", result.getPrimary(), "Alaska Airlines should be travel, not utilities");
        assertEquals(
                "travel", result.getDetailed(), "Alaska Airlines should be travel, not utilities");
    }

    @Test
    void testUnitedAirlinesTravelNotOther() {
        // Given: United Airlines transaction
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "UNITED AIRLINES";
        final String description = "Flight ticket";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "United Airlines should be travel");
        assertEquals("travel", result.getDetailed(), "United Airlines should be travel");
    }

    // ========== Shopping Transaction Tests ==========

    @Test
    void testLululemonShoppingNotTransportation() {
        // Given: Lululemon transaction that was incorrectly categorized as transportation
        final String primary = "TRANSPORTATION";
        final String detailed = "TRANSPORTATION";
        final String merchantName =
                "AGARWAL SUMIT KUMAR Platinum Lululemon Credit LULULEMON ATHLETICA USA B";
        final String description =
                "AGARWAL SUMIT KUMAR Platinum Lululemon Credit LULULEMON ATHLETICA USA B";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals(
                "shopping",
                result.getPrimary(),
                "Lululemon should be shopping, not transportation");
        assertEquals(
                "shopping",
                result.getDetailed(),
                "Lululemon should be shopping, not transportation");
    }

    @Test
    void testNikeShoppingNotOther() {
        // Given: Nike transaction
        final String primary = "GENERAL_MERCHANDISE";
        final String detailed = null;
        final String merchantName = "Nike Store";
        final String description = "Nike purchase";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("shopping", result.getPrimary(), "Nike should be shopping");
        assertEquals("shopping", result.getDetailed(), "Nike should be shopping");
    }

    @Test
    void testNordstromShoppingNotOther() {
        // Given: Nordstrom transaction
        final String primary = "GENERAL_MERCHANDISE";
        final String detailed = null;
        final String merchantName = "Nordstrom";
        final String description = "Clothing purchase";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("shopping", result.getPrimary(), "Nordstrom should be shopping");
        assertEquals("shopping", result.getDetailed(), "Nordstrom should be shopping");
    }

    // ========== Movie Theater/Entertainment Tests ==========

    @Test
    void testAMCTheaterEntertainmentNotEducation() {
        // Given: AMC movie theater transaction that was incorrectly categorized as education
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "AMC 2434 FACTORIA 8 BELLEVUE WA";
        final String description = "AMC 2434 FACTORIA 8 BELLEVUE WA";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals(
                "entertainment", result.getPrimary(), "AMC should be entertainment, not education");
        assertEquals(
                "entertainment",
                result.getDetailed(),
                "AMC should be entertainment, not education");
    }

    @Test
    void testRegalCinemaEntertainmentNotOther() {
        // Given: Regal Cinema transaction
        final String primary = "ENTERTAINMENT";
        final String detailed = null;
        final String merchantName = "Regal Cinemas";
        final String description = "Movie tickets";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("entertainment", result.getPrimary(), "Regal Cinema should be entertainment");
        assertEquals("entertainment", result.getDetailed(), "Regal Cinema should be entertainment");
    }

    @Test
    void testCinemarkEntertainmentNotOther() {
        // Given: Cinemark transaction
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "Cinemark";
        final String description = "Movie";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("entertainment", result.getPrimary(), "Cinemark should be entertainment");
        assertEquals("entertainment", result.getDetailed(), "Cinemark should be entertainment");
    }

    // ========== Airport Lounge Tests ==========

    @Test
    void testAXPCenturionLoungeTravelNotUtilities() {
        // Given: AXP Centurion Lounge transaction that was incorrectly categorized as utilities
        final String primary = "RENT_AND_UTILITIES";
        final String detailed = "UTILITIES";
        final String merchantName = "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER";
        final String description = "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals(
                "travel",
                result.getPrimary(),
                "AXP Centurion Lounge should be travel, not utilities");
        assertEquals(
                "travel",
                result.getDetailed(),
                "AXP Centurion Lounge should be travel, not utilities");
    }

    @Test
    void testPriorityPassLoungeTravelNotOther() {
        // Given: Priority Pass lounge transaction
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "Priority Pass Lounge";
        final String description = "Airport lounge access";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "Priority Pass should be travel");
        assertEquals("travel", result.getDetailed(), "Priority Pass should be travel");
    }

    @Test
    void testDeltaSkyClubTravelNotOther() {
        // Given: Delta Sky Club transaction
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "Delta Sky Club";
        final String description = "Airport lounge";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("travel", result.getPrimary(), "Delta Sky Club should be travel");
        assertEquals("travel", result.getDetailed(), "Delta Sky Club should be travel");
    }

    // ========== Direct Payment Tests ==========

    @Test
    void testDirectPayPaymentNotOther() {
        // Given: Direct payment transaction with positive amount that should be Payment type
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "1% Cashback Bonus +$0.06 DIRECTPAY FULL BALANCE";
        final String description = "1% Cashback Bonus +$0.06 DIRECTPAY FULL BALANCE";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(
                        primary,
                        detailed,
                        merchantName,
                        description,
                        null,
                        java.math.BigDecimal.valueOf(11.74));

        // Then
        assertNotNull(result);
        assertEquals("payment", result.getPrimary(), "DIRECTPAY should be payment, not other");
        assertEquals("payment", result.getDetailed(), "DIRECTPAY should be payment, not other");
    }

    @Test
    void testDirectPaymentPaymentNotOther() {
        // Given: Direct payment transaction
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "Direct Payment";
        final String description = "Direct payment to credit card";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("payment", result.getPrimary(), "Direct payment should be payment");
        assertEquals("payment", result.getDetailed(), "Direct payment should be payment");
    }

    @Test
    void testAutomatymentPaymentNotOther() {
        // Given: Automatyment (misspelling of automatic payment) transaction
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "Automatyment";
        final String description = "Automatyment payment";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("payment", result.getPrimary(), "Automatyment should be payment");
        assertEquals("payment", result.getDetailed(), "Automatyment should be payment");
    }

    // ========== Holdings Company Tests ==========

    @Test
    void testTRGHoldingsOtherNotDining() {
        // Given: TRG Holdings transaction that was incorrectly categorized as dining
        final String primary = "FOOD_AND_DRINK"; // Plaid incorrectly categorizes as dining
        final String detailed = "RESTAURANTS";
        final String merchantName = "TRG HOLDINGS LIMITED LONDON";
        final String description = "TRG HOLDINGS LIMITED LONDON";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("other", result.getPrimary(), "TRG Holdings should be other, not dining");
        assertEquals("other", result.getDetailed(), "TRG Holdings should be other, not dining");
    }

    @Test
    void testHoldingsLimitedOtherNotDining() {
        // Given: Generic holdings company transaction
        final String primary = "FOOD_AND_DRINK";
        final String detailed = "RESTAURANTS";
        final String merchantName = "ABC HOLDINGS LIMITED";
        final String description = "ABC HOLDINGS LIMITED";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("other", result.getPrimary(), "Holdings company should be other, not dining");
        assertEquals("other", result.getDetailed(), "Holdings company should be other, not dining");
    }

    @Test
    void testHoldingsCompanyOtherNotOtherCategory() {
        // Given: Holdings company with no specific category
        final String primary = "GENERAL_SERVICES";
        final String detailed = null;
        final String merchantName = "XYZ HOLDINGS LIMITED";
        final String description = "Business transaction";

        // When
        final PlaidCategoryMapper.CategoryMapping result =
                plaidCategoryMapper.mapPlaidCategory(primary, detailed, merchantName, description);

        // Then
        assertNotNull(result);
        assertEquals("other", result.getPrimary(), "Holdings company should be other");
        assertEquals("other", result.getDetailed(), "Holdings company should be other");
    }
}
