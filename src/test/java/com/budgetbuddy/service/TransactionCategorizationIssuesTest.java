package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Comprehensive tests for transaction categorization issues reported by user Tests all the specific
 * transaction categorization fixes
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TransactionCategorizationIssuesTest {

    private static final String UTILITIES = "utilities";
    private static final String EDUCATION = "education";
    private static final String SHOPPING = "shopping";
    private static final String DINING = "dining";
    private static final String SUBSCRIPTIONS = "subscriptions";
    private static final String GROCERIES = "groceries";
    private static final String TRAVEL = "travel";
    private static final String PET = "pet";
    private static final String TRANSPORTATION = "transportation";

    @Autowired private TransactionTypeCategoryService service;

    @Autowired private CSVImportService csvImportService;

    private AccountTable creditCardAccount;
    private AccountTable wellsFargoCreditCardAccount;
    private AccountTable checkingAccount;

    @BeforeEach
    void setUp() {
        // Setup credit card account
        creditCardAccount = new AccountTable();
        creditCardAccount.setAccountId("credit-card-account-id");
        creditCardAccount.setAccountType("credit");
        creditCardAccount.setAccountSubtype("credit card");
        creditCardAccount.setInstitutionName("Chase");
        creditCardAccount.setAccountName("Chase Credit Card");

        // Setup Wells Fargo credit card account
        wellsFargoCreditCardAccount = new AccountTable();
        wellsFargoCreditCardAccount.setAccountId("wells-fargo-cc-id");
        wellsFargoCreditCardAccount.setAccountType("credit");
        wellsFargoCreditCardAccount.setAccountSubtype("credit card");
        wellsFargoCreditCardAccount.setInstitutionName("Wells Fargo");
        wellsFargoCreditCardAccount.setAccountName("WF Credit Card");

        // Setup checking account
        checkingAccount = new AccountTable();
        checkingAccount.setAccountId("checking-account-id");
        checkingAccount.setAccountType("depository");
        checkingAccount.setAccountSubtype("checking");
    }

    // ========== Wells Fargo Credit Card Payment Tests ==========

    @Test
    void testWellsFargoCreditCardPaymentPositiveAmount() {
        // Given: Wells Fargo credit card payment with negative amount (should be converted to
        // positive)
        // "WF Credit Card   AUTO PAY                   PPD ID: 50260000" with amount -447.54
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "payment",
                        "payment",
                        wellsFargoCreditCardAccount,
                        "WF CREDIT CARD AUTO PAY PPD ID:",
                        "WF Credit Card   AUTO PAY                   PPD ID: 50260000",
                        BigDecimal.valueOf(-447.54),
                        null,
                        null,
                        "CSV", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        wellsFargoCreditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-447.54),
                        null,
                        "WF Credit Card   AUTO PAY                   PPD ID: 50260000",
                        null);

        // Then: Should be payment category and PAYMENT type
        assertNotNull(categoryResult);
        assertEquals("payment", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.PAYMENT, typeResult.getTransactionType());
    }

    // ========== Subscription Refund Tests ==========

    @Test
    void testSubscriptionRefundBarrons() {
        // Given: BARRONS refund (positive amount on credit card)
        // "Platinum Digital Entertainment Credit D J*BARRONS" with amount 4.41
        // UPDATED: Barrons is now categorized as education (financial education publication), not
        // subscriptions
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        SUBSCRIPTIONS, // Importer says subscriptions, but should be overridden to
                        // education
                        SUBSCRIPTIONS,
                        creditCardAccount,
                        "Platinum Digital Entertainment Credit D J*BARRONS",
                        "Platinum Digital Entertainment Credit D J*BARRONS",
                        BigDecimal.valueOf(4.41),
                        null,
                        null,
                        "PDF",
                        null);

        // Then: Should be education (financial education publication), not subscriptions or credit
        assertNotNull(categoryResult);
        assertEquals(
                EDUCATION,
                categoryResult.getCategoryPrimary(),
                "Barrons should be categorized as education (financial education publication), not subscriptions");
    }

    @Test
    void testSubscriptionRefundWalmartPlus() {
        // Given: Walmart+ subscription refund (positive amount on credit card)
        // "Platinum Walmart+ Credit WMT PLUS Jun 2025 02737" with amount 14.27
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        GROCERIES,
                        GROCERIES,
                        creditCardAccount,
                        "Platinum Walmart+ Credit WMT PLUS Jun 2025 02737",
                        "Platinum Walmart+ Credit WMT PLUS Jun 2025 02737",
                        BigDecimal.valueOf(14.27),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be groceries, not credit
        assertNotNull(categoryResult);
        assertEquals(GROCERIES, categoryResult.getCategoryPrimary());
    }

    @Test
    void testGroceriesRefundCostco() {
        // Given: Costco refund (positive amount on credit card)
        // "WWW COSTCO COM 800-955-2292 WA" with amount 117.17
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        GROCERIES,
                        GROCERIES,
                        creditCardAccount,
                        "WWW COSTCO COM 800-955-2292 WA",
                        "WWW COSTCO COM 800-955-2292 WA",
                        BigDecimal.valueOf(117.17),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be groceries, not credit
        assertNotNull(categoryResult);
        assertEquals(GROCERIES, categoryResult.getCategoryPrimary());
    }

    // ========== Education Category Tests ==========

    @Test
    void testEducationBellevueSchoolDistrict() {
        // Given: Bellevue School District transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other",
                        "other",
                        checkingAccount,
                        "BELLEVUE SCHOOL DISTRI BELLEVUE WA",
                        "BELLEVUE SCHOOL DISTRI BELLEVUE WA",
                        BigDecimal.valueOf(-101),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-101),
                        null,
                        "BELLEVUE SCHOOL DISTRI BELLEVUE WA",
                        null);

        // Then: Should be education, not other
        assertNotNull(categoryResult);
        assertEquals(EDUCATION, categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    @Test
    void testEducationAnki() {
        // Given: Anki transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other",
                        "other",
                        creditCardAccount,
                        "SP ANKI REMOTE NEWBURGH IN +17864744370",
                        "SP ANKI REMOTE NEWBURGH IN +17864744370",
                        BigDecimal.valueOf(-51.03),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be education, not other
        assertNotNull(categoryResult);
        assertEquals(EDUCATION, categoryResult.getCategoryPrimary());
    }

    @Test
    void testEducationAAMCExam() {
        // Given: AAMC exam transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "entertainment",
                        "entertainment",
                        creditCardAccount,
                        "MUDIT AGARWAL VUE*AAMC EXAM BLOOMINGTON 0074-7349-7152|26088021|1",
                        "MUDIT AGARWAL VUE*AAMC EXAM BLOOMINGTON 0074-7349-7152|26088021|1",
                        BigDecimal.valueOf(170),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be education, not entertainment
        assertNotNull(categoryResult);
        assertEquals(EDUCATION, categoryResult.getCategoryPrimary());
    }

    @Test
    void testEducationGurukul() {
        // Given: Gurukul (Indian school) transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other",
                        "other",
                        creditCardAccount,
                        "PAYPAL *GURUKUL GURUKUL W 0000000000 WA 0000000000",
                        "PAYPAL *GURUKUL GURUKUL W 0000000000 WA 0000000000",
                        BigDecimal.valueOf(-400),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be education, not other
        assertNotNull(categoryResult);
        assertEquals(EDUCATION, categoryResult.getCategoryPrimary());
    }

    @Test
    void testEducationTyeeMiddleSchool() {
        // Given: Tyee Middle School transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "charity",
                        "charity",
                        checkingAccount,
                        "TYEE MIDDLE SCHOOL PTS BELLEVUE WA",
                        "TYEE MIDDLE SCHOOL PTS BELLEVUE WA",
                        BigDecimal.valueOf(-100),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be education, not charity
        assertNotNull(categoryResult);
        assertEquals(EDUCATION, categoryResult.getCategoryPrimary());
    }

    @Test
    void testEducationUniversityBookStore() {
        // Given: University Book Store transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        checkingAccount,
                        "UNIVERSITY BOOK STORE, SEATTLE WA",
                        "UNIVERSITY BOOK STORE, SEATTLE WA",
                        BigDecimal.valueOf(-66.24),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be education, not utilities
        assertNotNull(categoryResult);
        assertEquals(EDUCATION, categoryResult.getCategoryPrimary());
    }

    // ========== Travel Category Tests ==========

    @Test
    void testTravelCenturionLounge() {
        // Given: Centurion Lounge transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        creditCardAccount,
                        "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER",
                        "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER",
                        BigDecimal.valueOf(-30),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be travel, not utilities
        assertNotNull(categoryResult);
        assertEquals(TRAVEL, categoryResult.getCategoryPrimary());
    }

    // ========== Transportation Category Tests ==========

    @Test
    void testTransportationLyft() {
        // Given: Lyft ride transaction (not subscription)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        SUBSCRIPTIONS,
                        SUBSCRIPTIONS,
                        creditCardAccount,
                        "LYFT *RIDE FRI 5PM LYFT.COM CA",
                        "LYFT *RIDE FRI 5PM LYFT.COM CA",
                        BigDecimal.valueOf(-67.7),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be transportation, not subscriptions
        assertNotNull(categoryResult);
        assertEquals(TRANSPORTATION, categoryResult.getCategoryPrimary());
    }

    @Test
    void testTransportationExxon() {
        // Given: Exxon gas station transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        SUBSCRIPTIONS,
                        SUBSCRIPTIONS,
                        creditCardAccount,
                        "EXXON ZOOMERZ #967 KINGSTON TN",
                        "EXXON ZOOMERZ #967 KINGSTON TN",
                        BigDecimal.valueOf(-17.68),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be transportation, not subscriptions
        assertNotNull(categoryResult);
        assertEquals(TRANSPORTATION, categoryResult.getCategoryPrimary());
    }

    @Test
    void testTransportationPayByPhone() {
        // Given: Pay by Phone parking transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        creditCardAccount,
                        "UW PAY BY PHONE SEATTLE WA 206-685-1553",
                        "UW PAY BY PHONE SEATTLE WA 206-685-1553",
                        BigDecimal.valueOf(-21),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be transportation, not utilities
        assertNotNull(categoryResult);
        assertEquals(TRANSPORTATION, categoryResult.getCategoryPrimary());
    }

    // ========== Dining Category Tests ==========

    @Test
    void testDiningTSTDeepDive() {
        // Given: TST* DEEP DIVE transaction (Toast POS system)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        creditCardAccount,
                        "TST* DEEP DIVE SEATTLE WA",
                        "TST* DEEP DIVE SEATTLE WA",
                        BigDecimal.valueOf(-50),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-50),
                        null,
                        "TST* DEEP DIVE SEATTLE WA",
                        null);

        // Then: Should be dining, not utilities, and EXPENSE, not LOAN
        assertNotNull(categoryResult);
        assertEquals(DINING, categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    @Test
    void testDiningTPD() {
        // Given: TPD (Top Pot Donuts) transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        checkingAccount,
                        "TPD 5TH AVE 102 SEATTLE WA",
                        "TPD 5TH AVE 102 SEATTLE WA",
                        BigDecimal.valueOf(-54.89),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be dining, not utilities
        assertNotNull(categoryResult);
        assertEquals(DINING, categoryResult.getCategoryPrimary());
    }

    @Test
    void testDiningSQSunnyHoney() {
        // Given: SQ* (Square POS) transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        checkingAccount,
                        "SQ *SUNNY HONEY COMPAN Seattle WA",
                        "SQ *SUNNY HONEY COMPAN Seattle WA",
                        BigDecimal.valueOf(-5),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be dining, not utilities
        assertNotNull(categoryResult);
        assertEquals(DINING, categoryResult.getCategoryPrimary());
    }

    @Test
    void testDiningBurgerAndKabobHut() {
        // Given: Burger and Kabob Hut transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        checkingAccount,
                        "BURGER AND KABOB HUT SEATTLE WA",
                        "BURGER AND KABOB HUT SEATTLE WA",
                        BigDecimal.valueOf(-57.46),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be dining, not utilities
        assertNotNull(categoryResult);
        assertEquals(DINING, categoryResult.getCategoryPrimary());
    }

    // ========== Health Category Tests ==========

    @Test
    void testHealthBadmintonClub() {
        // Given: Seattle Badminton Club transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        checkingAccount,
                        "SEATTLE BADMINTON CLUB KIRKLAND WA",
                        "SEATTLE BADMINTON CLUB KIRKLAND WA",
                        BigDecimal.valueOf(-22.06),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be health, not utilities
        assertNotNull(categoryResult);
        assertEquals("health", categoryResult.getCategoryPrimary());
    }

    // ========== Pet Category Tests ==========

    @Test
    void testPetPetcareClinic() {
        // Given: Petcare Clinic transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "healthcare",
                        "healthcare",
                        checkingAccount,
                        "PETCARE CLINIC BELLEVU BELLEVUE WA",
                        "PETCARE CLINIC BELLEVU BELLEVUE WA",
                        BigDecimal.valueOf(-113.92),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be pet, not healthcare
        assertNotNull(categoryResult);
        assertEquals(PET, categoryResult.getCategoryPrimary());
    }

    // ========== Groceries Category Tests ==========

    @Test
    void testGroceriesFredMeyer() {
        // Given: Fred Meyer transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other",
                        "other",
                        checkingAccount,
                        "FRED-MEYER #0658 ISSAQUAH WA",
                        "FRED-MEYER #0658 ISSAQUAH WA",
                        BigDecimal.valueOf(-6),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be groceries, not other
        assertNotNull(categoryResult);
        assertEquals(GROCERIES, categoryResult.getCategoryPrimary());
    }

    // ========== Lululemon Transaction Tests ==========

    @Test
    void testLululemonShoppingNotTransportation() {
        // Given: Lululemon purchase transaction that was incorrectly categorized as transportation
        // Transaction: "LULULEMON ATHLETICA USA B TO C (877)263-9300 CA CLOTHING 877-263-9300"
        // Amount: -129.26 (negative = expense)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        null, // No importer category (from PDF import)
                        null,
                        creditCardAccount,
                        "LULULEMON ATHLETICA USA B TO C (877)263-9300 CA CLOTHING 877-263-9300",
                        "LULULEMON ATHLETICA USA B TO C (877)263-9300 CA CLOTHING 877-263-9300",
                        BigDecimal.valueOf(-129.26),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-129.26),
                        null,
                        "LULULEMON ATHLETICA USA B TO C (877)263-9300 CA CLOTHING 877-263-9300",
                        null);

        // Then: Should be shopping category and EXPENSE type (not transportation/PAYMENT)
        assertNotNull(categoryResult);
        assertEquals(
                SHOPPING,
                categoryResult.getCategoryPrimary(),
                "Lululemon should be categorized as shopping, not transportation");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Lululemon purchase should be EXPENSE, not PAYMENT");
    }

    @Test
    void testLululemonWithShoppingImporterCategory() {
        // Given: Lululemon transaction with importer category SHOPPING (from CSV/PDF import)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        SHOPPING,
                        SHOPPING,
                        creditCardAccount,
                        "LULULEMON ATHLETICA USA B TO C (877)263-9300 CA CLOTHING 877-263-9300",
                        "LULULEMON ATHLETICA USA B TO C (877)263-9300 CA CLOTHING 877-263-9300",
                        BigDecimal.valueOf(-129.26),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-129.26),
                        null,
                        "LULULEMON ATHLETICA USA B TO C (877)263-9300 CA CLOTHING 877-263-9300",
                        null);

        // Then: Should preserve shopping category and be EXPENSE type
        assertNotNull(categoryResult);
        assertEquals(SHOPPING, categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    // ========== Xfinity/Comcast Transaction Tests ==========

    @Test
    void testXfinityMobileUtilitiesNotTransportation() {
        // Given: Xfinity Mobile transaction that was incorrectly categorized as transportation
        // Transaction: "2469216AD2YMLKAPM XFINITY MOBILE 888-936-4968 PA"
        // Amount: -158.03 (negative = expense)
        // Importer category: UTILITIES (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES,
                        UTILITIES,
                        creditCardAccount,
                        "2469216AD2YMLKAPM XFINITY MOBILE 888-936-4968 PA",
                        "2469216AD2YMLKAPM XFINITY MOBILE 888-936-4968 PA",
                        BigDecimal.valueOf(-158.03),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-158.03),
                        null,
                        "2469216AD2YMLKAPM XFINITY MOBILE 888-936-4968 PA",
                        null);

        // Then: Should be utilities category and EXPENSE type (not transportation/PAYMENT)
        assertNotNull(categoryResult);
        assertEquals(
                UTILITIES,
                categoryResult.getCategoryPrimary(),
                "Xfinity Mobile should be categorized as utilities, not transportation");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Xfinity Mobile bill should be EXPENSE, not PAYMENT");
    }

    @Test
    void testXfinityUtilitiesNotTransportation() {
        // Given: Xfinity (internet/cable) transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        null,
                        null,
                        creditCardAccount,
                        "XFINITY INTERNET PAYMENT",
                        "XFINITY INTERNET PAYMENT",
                        BigDecimal.valueOf(-89.99),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-89.99),
                        null,
                        "XFINITY INTERNET PAYMENT",
                        null);

        // Then: Should be utilities category and EXPENSE type
        assertNotNull(categoryResult);
        assertEquals(UTILITIES, categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    @Test
    void testComcastUtilitiesNotTransportation() {
        // Given: Comcast transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        null,
                        null,
                        creditCardAccount,
                        "COMCAST / XFINITY",
                        "COMCAST / XFINITY",
                        BigDecimal.valueOf(-79.99),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-79.99),
                        null,
                        "COMCAST / XFINITY",
                        null);

        // Then: Should be utilities category and EXPENSE type
        assertNotNull(categoryResult);
        assertEquals(UTILITIES, categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    // ========== Passport Services Transaction Tests ==========

    @Test
    void testPassportServicesTravelNotTransfer() {
        // Given: Check transaction for passport services that was incorrectly categorized as
        // transfer
        // Transaction: "CHECK # 0165      PASSPORTSERVICES PAYMENT           ARC ID: 1900000119"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "transfer", // Importer incorrectly categorized as transfer
                        "transfer",
                        checkingAccount,
                        "CHECK PASSPORTSERVICES PAYMENT ARC ID:",
                        "CHECK # 0165      PASSPORTSERVICES PAYMENT           ARC ID: 1900000119",
                        BigDecimal.valueOf(-150.00),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-150.00),
                        null,
                        "CHECK # 0165      PASSPORTSERVICES PAYMENT           ARC ID: 1900000119",
                        null);

        // Then: Should be travel category and EXPENSE type (not transfer)
        assertNotNull(categoryResult);
        assertEquals(
                TRAVEL,
                categoryResult.getCategoryPrimary(),
                "Passport services should be categorized as travel, not transfer");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Passport services expense should be EXPENSE type");
    }

    @Test
    void testVisaServiceTravel() {
        // Given: Visa application service transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        null,
                        null,
                        checkingAccount,
                        "VISA APPLICATION SERVICE",
                        "VISA APPLICATION SERVICE PAYMENT",
                        BigDecimal.valueOf(-200.00),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be travel category
        assertNotNull(categoryResult);
        assertEquals(
                TRAVEL,
                categoryResult.getCategoryPrimary(),
                "Visa service should be categorized as travel");
    }

    @Test
    void testGlobalEntryTravel() {
        // Given: Global Entry application transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        null,
                        null,
                        creditCardAccount,
                        "GLOBAL ENTRY APPLICATION",
                        "GLOBAL ENTRY APPLICATION FEE",
                        BigDecimal.valueOf(-100.00),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be travel category
        assertNotNull(categoryResult);
        assertEquals(
                TRAVEL,
                categoryResult.getCategoryPrimary(),
                "Global Entry should be categorized as travel");
    }

    @Test
    void testTSAPrecheckTravel() {
        // Given: TSA PreCheck application transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        null,
                        null,
                        creditCardAccount,
                        "TSA PRECHECK",
                        "TSA PRECHECK APPLICATION FEE",
                        BigDecimal.valueOf(-78.00),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be travel category
        assertNotNull(categoryResult);
        assertEquals(
                TRAVEL,
                categoryResult.getCategoryPrimary(),
                "TSA PreCheck should be categorized as travel");
    }

    // ========== Money Transfer Services Tests ==========

    @Test
    void testRemitlyTransferNotTravel() {
        // Given: Remitly money transfer transaction that was incorrectly categorized as travel
        // Transaction: "Remitly United S PAYMENTS   720176389717143 CCD ID: 2452441988"
        // Amount: -20000 (negative = expense/transfer)
        // Importer category: "transfer" (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "transfer", // Importer correctly categorized as transfer
                        "transfer",
                        checkingAccount,
                        "REMITLY UNITED S PAYMENTS CCD ID:",
                        "Remitly United S PAYMENTS   720176389717143 CCD ID: 2452441988",
                        BigDecimal.valueOf(-20_000),
                        null,
                        null,
                        "CSV", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-20_000),
                        null,
                        "Remitly United S PAYMENTS   720176389717143 CCD ID: 2452441988",
                        null);

        // Then: Should be transfer category (not travel, despite "United" keyword)
        assertNotNull(categoryResult);
        assertEquals(
                "transfer",
                categoryResult.getCategoryPrimary(),
                "Remitly money transfer should be categorized as transfer, not travel (United is not United Airlines)");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Remitly transfer should be EXPENSE type");
    }

    @Test
    void testWesternUnionTransfer() {
        // Given: Western Union money transfer transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        null,
                        null,
                        checkingAccount,
                        "WESTERN UNION",
                        "WESTERN UNION MONEY TRANSFER",
                        BigDecimal.valueOf(-500.00),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be transfer category
        assertNotNull(categoryResult);
        assertEquals(
                "transfer",
                categoryResult.getCategoryPrimary(),
                "Western Union should be categorized as transfer");
    }

    @Test
    void testWiseTransfer() {
        // Given: Wise (formerly TransferWise) money transfer transaction
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        null,
                        null,
                        creditCardAccount,
                        "WISE",
                        "WISE MONEY TRANSFER",
                        BigDecimal.valueOf(-1000.00),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be transfer category
        assertNotNull(categoryResult);
        assertEquals(
                "transfer",
                categoryResult.getCategoryPrimary(),
                "Wise should be categorized as transfer");
    }

    // ========== Financial/Account Terms Tests ==========

    @Test
    void testPromotionalAPROtherNotTravel() {
        // Given: Promotional APR ended transaction that was incorrectly categorized as travel
        // Transaction: "OFFER 04 PROMOTIONAL APR ENDED 12/12/25"
        // Importer category: "other" (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other", // Importer correctly categorized as other
                        "other",
                        creditCardAccount,
                        "OFFER 04 PROMOTIONAL APR ENDED 12/12/25",
                        "OFFER 04 PROMOTIONAL APR ENDED 12/12/25",
                        BigDecimal.valueOf(-0.00), // Could be any amount
                        null,
                        null,
                        "CSV", null);

        // Then: Should be other category (not travel, despite potential keyword matches)
        assertNotNull(categoryResult);
        assertEquals(
                "other",
                categoryResult.getCategoryPrimary(),
                "Promotional APR/account terms should be categorized as other, not travel");
    }

    @Test
    void testFinancialTermsOtherNotTravel() {
        // Given: Transaction with financial/accounting terms
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other",
                        "other",
                        creditCardAccount,
                        "CREDIT CARD STATEMENT",
                        "CREDIT CARD STATEMENT",
                        BigDecimal.valueOf(-0.00),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be other category (not travel)
        assertNotNull(categoryResult);
        assertEquals(
                "other",
                categoryResult.getCategoryPrimary(),
                "Financial/accounting terms should be categorized as other, not travel");
    }

    // ========== Healthcare False Positive Tests ==========

    @Test
    void testOfferStandardPurchOtherNotHealthcare() {
        // Given: Offer transaction that was incorrectly categorized as healthcare
        // Transaction: "OFFER 04 MOVED TO STANDARD PURCH"
        // Importer category: "other" (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other", // Importer correctly categorized as other
                        "other",
                        creditCardAccount,
                        "OFFER 04 MOVED TO STANDARD PURCH",
                        "OFFER 04 MOVED TO STANDARD PURCH",
                        BigDecimal.valueOf(-0.00),
                        null,
                        null,
                        "CSV", null);

        // Then: Should be other category (not healthcare)
        assertNotNull(categoryResult);
        assertEquals(
                "other",
                categoryResult.getCategoryPrimary(),
                "Offer/standard purch financial terms should be categorized as other, not healthcare");
    }

    @Test
    void testTRGHoldingsOtherOrDiningNotHealthcare() {
        // Given: TRG Holdings transaction that was incorrectly categorized as healthcare
        // Transaction: "TRG HOLDINGS LIMITED LONDON"
        // Importer category: "other" (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other", // Importer correctly categorized as other
                        "other",
                        creditCardAccount,
                        "TRG HOLDINGS LIMITED LONDON",
                        "TRG HOLDINGS LIMITED LONDON",
                        BigDecimal.valueOf(-13.46),
                        null,
                        null,
                        "CSV", null);

        // Then: Should be other or dining category (not healthcare)
        assertNotNull(categoryResult);
        assertTrue(
                "other".equals(categoryResult.getCategoryPrimary())
                        || DINING.equals(categoryResult.getCategoryPrimary()),
                "TRG Holdings should be categorized as other or dining, not healthcare");
    }

    @Test
    void testDepositIDNumberDepositNotHealthcare() {
        // Given: Deposit transaction that was incorrectly categorized as healthcare
        // Transaction: "DEPOSIT ID NUMBER 716081" with positive amount
        // Amount: 1000 (positive = deposit/income)
        // Importer category: "other" (should be deposit)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other",
                        "other",
                        checkingAccount,
                        "DEPOSIT ID NUMBER",
                        "DEPOSIT  ID NUMBER 716081",
                        BigDecimal.valueOf(1000),
                        null,
                        null,
                        "CSV", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(1000),
                        null,
                        "DEPOSIT  ID NUMBER 716081",
                        null);

        // Then: Should be deposit category and INCOME type (not healthcare/expense)
        assertNotNull(categoryResult);
        assertEquals(
                "deposit",
                categoryResult.getCategoryPrimary(),
                "Deposit transaction should be categorized as deposit, not healthcare");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.INCOME,
                typeResult.getTransactionType(),
                "Deposit transaction should be INCOME type, not EXPENSE");
    }

    @Test
    void testUSPSOtherNotHealthcare() {
        // Given: USPS transaction that was incorrectly categorized as healthcare
        // Transaction: "USPS PO 5406030193 BELLEVUE WA"
        // Importer category: "other" (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other", // Importer correctly categorized as other
                        "other",
                        creditCardAccount,
                        "USPS PO 5406030193 BELLEVUE WA",
                        "USPS PO 5406030193 BELLEVUE WA",
                        BigDecimal.valueOf(-10.00),
                        null,
                        null,
                        "CSV", null);

        // Then: Should be other category (not healthcare)
        assertNotNull(categoryResult);
        assertEquals(
                "other",
                categoryResult.getCategoryPrimary(),
                "USPS/post office should be categorized as other (postage), not healthcare");
    }

    @Test
    void testAOWSocialClubEntertainmentOrOtherNotHealthcare() {
        // Given: AOW social club transaction that was incorrectly categorized as healthcare
        // Transaction: "AOW-AGRAWALSWA MIDDLETOWN DE"
        // Importer category: "other" (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other", // Importer correctly categorized as other
                        "other",
                        creditCardAccount,
                        "AOW-AGRAWALSWA MIDDLETOWN DE",
                        "AOW-AGRAWALSWA MIDDLETOWN DE",
                        BigDecimal.valueOf(-135),
                        null,
                        null,
                        "CSV", null);

        // Then: Should be entertainment or other category (not healthcare)
        assertNotNull(categoryResult);
        assertTrue(
                "entertainment".equals(categoryResult.getCategoryPrimary())
                        || "other".equals(categoryResult.getCategoryPrimary()),
                "AOW social club should be categorized as entertainment or other, not healthcare");
    }

    // ========== Gas Station Categorization Tests ==========

    @Test
    void testBuceesTransportationNotShopping() {
        // Given: Buc-ee's transaction that was incorrectly categorized as shopping/PAYMENT
        // Transaction: "BUC-EE'S #50 CROSSVILLE TN"
        // Importer category: SHOPPING (incorrect - should be transportation)
        // Amount: -18.43 (negative = expense)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        SHOPPING, // Importer incorrectly categorized as shopping
                        SHOPPING,
                        creditCardAccount,
                        "BUC-EE'S #50 CROSSVILLE TN",
                        "BUC-EE'S #50 CROSSVILLE TN",
                        BigDecimal.valueOf(-18.43),
                        null,
                        null,
                        "CSV", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-18.43),
                        null,
                        "BUC-EE'S #50 CROSSVILLE TN",
                        null);

        // Then: Should be transportation category and EXPENSE type (not shopping/PAYMENT)
        assertNotNull(categoryResult);
        assertEquals(
                TRANSPORTATION,
                categoryResult.getCategoryPrimary(),
                "Buc-ee's (gas station) should be categorized as transportation, not shopping");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Buc-ee's transaction should be EXPENSE type, not PAYMENT");
    }

    // ========== Barrons Education Tests ==========

    @Test
    void testBarronsExpenseEducationNotSubscription() {
        // Given: Barrons expense transaction that was incorrectly categorized as subscriptions
        // Transaction: "D J*BARRONS 800-544-0422 NJ SUBSRIPTION"
        // Amount: -4.41 (negative = expense)
        // Importer category: SUBSCRIPTIONS (should be overridden to education)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        SUBSCRIPTIONS, // Importer incorrectly categorized as subscriptions
                        SUBSCRIPTIONS,
                        creditCardAccount,
                        "D J*BARRONS 800-544-0422 NJ SUBSRIPTION",
                        "D J*BARRONS 800-544-0422 NJ SUBSRIPTION",
                        BigDecimal.valueOf(-4.41),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-4.41),
                        null,
                        "D J*BARRONS 800-544-0422 NJ SUBSRIPTION",
                        null);

        // Then: Should be education category and EXPENSE type (not subscriptions)
        assertNotNull(categoryResult);
        assertEquals(
                EDUCATION,
                categoryResult.getCategoryPrimary(),
                "Barrons (financial education publication) should be categorized as education, not subscriptions");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Barrons expense transaction should be EXPENSE type");
    }

    @Test
    void testBarronsCreditEducationNotSubscription() {
        // Given: Barrons credit transaction (Platinum Digital Entertainment Credit)
        // Transaction: "Platinum Digital Entertainment Credit D J*BARRONS"
        // Amount: 4.41 (positive = credit/refund)
        // Importer category: SUBSCRIPTIONS (should be overridden to education)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        SUBSCRIPTIONS, // Importer incorrectly categorized as subscriptions
                        SUBSCRIPTIONS,
                        creditCardAccount,
                        "AGARWAL SUMIT KUMAR Platinum Digital Entertainment Credit D J*BARRONS",
                        "AGARWAL SUMIT KUMAR Platinum Digital Entertainment Credit D J*BARRONS",
                        BigDecimal.valueOf(4.41),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(4.41),
                        null,
                        "AGARWAL SUMIT KUMAR Platinum Digital Entertainment Credit D J*BARRONS",
                        null);

        // Then: Should be education category (matching the expense) and EXPENSE type (credit card
        // credit)
        assertNotNull(categoryResult);
        assertEquals(
                EDUCATION,
                categoryResult.getCategoryPrimary(),
                "Barrons credit (refund) should be categorized as education to match the expense, not subscriptions");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Barrons credit transaction should be EXPENSE type (credit card credit)");
    }

    // ========== Shopping Categorization Tests ==========

    @Test
    void testCharlesTyrwhittShoppingNotCredit() {
        // Given: Charles Tyrwhitt Shirts transaction (expense) and credit
        // Transaction 1: "CHARLES TYRWHITT SHIRTS LIMITED WILMINGTON" (expense)
        final TransactionTypeCategoryService.CategoryResult categoryResult1 =
                service.determineCategory(
                        "other",
                        "other",
                        creditCardAccount,
                        "CHARLES TYRWHITT SHIRTS LIMITED WILMINGTON",
                        "CHARLES TYRWHITT SHIRTS LIMITED WILMINGTON",
                        BigDecimal.valueOf(-50.00),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult1 =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult1.getCategoryPrimary(),
                        categoryResult1.getCategoryDetailed(),
                        BigDecimal.valueOf(-50.00),
                        null,
                        "CHARLES TYRWHITT SHIRTS LIMITED WILMINGTON",
                        null);

        // Transaction 2: "CHARLES TYRWHITT SHIRTS LIMITED WILMINGTON Amex Offer Credit" (credit)
        final TransactionTypeCategoryService.CategoryResult categoryResult2 =
                service.determineCategory(
                        "other",
                        "other",
                        creditCardAccount,
                        "CHARLES TYRWHITT SHIRTS LIMITED WILMINGTON Amex Offer Credit",
                        "CHARLES TYRWHITT SHIRTS LIMITED WILMINGTON Amex Offer Credit",
                        BigDecimal.valueOf(10.00),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult2 =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult2.getCategoryPrimary(),
                        categoryResult2.getCategoryDetailed(),
                        BigDecimal.valueOf(10.00),
                        null,
                        "CHARLES TYRWHITT SHIRTS LIMITED WILMINGTON Amex Offer Credit",
                        null);

        // Then: Both should be shopping category and EXPENSE type (not credit)
        assertNotNull(categoryResult1);
        assertEquals(
                SHOPPING,
                categoryResult1.getCategoryPrimary(),
                "Charles Tyrwhitt (shirts) should be categorized as shopping");
        assertNotNull(typeResult1);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult1.getTransactionType(),
                "Charles Tyrwhitt expense should be EXPENSE type");

        assertNotNull(categoryResult2);
        assertEquals(
                SHOPPING,
                categoryResult2.getCategoryPrimary(),
                "Charles Tyrwhitt credit should be categorized as shopping to match expense");
        assertNotNull(typeResult2);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult2.getTransactionType(),
                "Charles Tyrwhitt credit should be EXPENSE type (credit card credit)");
    }

    @Test
    void testEsteeLauderShoppingNotCredit() {
        // Given: Estee Lauder transaction (credit/refund from Amex Offer)
        // Transaction: "ESTEE LAUDER ONLINE MELVILLE Amex Offer Credit"
        // Amount: 15 (positive = credit)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other",
                        "other",
                        creditCardAccount,
                        "GARIMA DIPTI AGARWAL ESTEE LAUDER ONLINE MELVILLE Amex Offer Credit",
                        "GARIMA DIPTI AGARWAL ESTEE LAUDER ONLINE MELVILLE Amex Offer Credit",
                        BigDecimal.valueOf(15),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(15),
                        null,
                        "GARIMA DIPTI AGARWAL ESTEE LAUDER ONLINE MELVILLE Amex Offer Credit",
                        null);

        // Then: Should be shopping category and EXPENSE type (not credit)
        assertNotNull(categoryResult);
        assertEquals(
                SHOPPING,
                categoryResult.getCategoryPrimary(),
                "Estee Lauder (cosmetics/skincare) should be categorized as shopping");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Estee Lauder credit should be EXPENSE type (credit card credit), not categorized as 'credit'");
    }

    @Test
    void testAXPCenturionLoungeTravelNotUtilities() {
        // Given: AXP Centurion Lounge transaction
        // Transaction: "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER"
        // Importer category: UTILITIES (incorrect - should be travel)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES, // Importer incorrectly categorized as utilities
                        UTILITIES,
                        creditCardAccount,
                        "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER",
                        "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER",
                        BigDecimal.valueOf(-30),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-30),
                        null,
                        "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER",
                        null);

        // Then: Should be travel category and EXPENSE type (not utilities)
        assertNotNull(categoryResult);
        assertEquals(
                TRAVEL,
                categoryResult.getCategoryPrimary(),
                "AXP Centurion Lounge should be categorized as travel, not utilities");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Centurion Lounge transaction should be EXPENSE type");
    }

    // ========== Education Overmatching Fix Tests ==========

    @Test
    void testOnlineTransferToTransferNotEducation() {
        // Given: Online transfer to checking account (expense)
        // Transaction: "Online Transfer to CHK ...9994 transaction#: 27390930759 12/19"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "transfer",
                        "transfer",
                        checkingAccount,
                        "ONLINE TRANSFER TO CHK ...9994 TRANSACTION#: 12/19",
                        "Online Transfer to CHK ...9994 transaction#: 27390930759 12/19",
                        BigDecimal.valueOf(-600),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-600),
                        null,
                        "Online Transfer to CHK ...9994 transaction#: 27390930759 12/19",
                        null);

        // Then: Should be transfer category and EXPENSE type (not education)
        assertNotNull(categoryResult);
        assertEquals(
                "transfer",
                categoryResult.getCategoryPrimary(),
                "Online transfer to checking should be 'transfer', not 'education'");
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    @Test
    void testOnlineTransferFromDepositNotEducation() {
        // Given: Online transfer from investment account (income/deposit)
        // Transaction: "Online Transfer 27265796721 from Morganstanley #########7477 transaction #:
        // 27265796721 12/10"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "deposit",
                        "deposit",
                        checkingAccount,
                        "ONLINE TRANSFER FROM MORGANSTANLEY ######## TRANSACTION #: 12/10",
                        "Online Transfer 27265796721 from Morganstanley #########7477 transaction #: 27265796721 12/10",
                        BigDecimal.valueOf(10_000),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(10_000),
                        null,
                        "Online Transfer 27265796721 from Morganstanley #########7477 transaction #: 27265796721 12/10",
                        null);

        // Then: Should be deposit category and INCOME type (not education)
        assertNotNull(categoryResult);
        assertEquals(
                "deposit",
                categoryResult.getCategoryPrimary(),
                "Online transfer from investment should be 'deposit', not 'education'");
        assertNotNull(typeResult);
        assertEquals(TransactionType.INCOME, typeResult.getTransactionType());
    }

    @Test
    void testVIASATTravelNotEducation() {
        // Given: VIASAT transaction (satellite internet for travel)
        // Transaction: "AplPay VIASAT, INC. CARLSBAD CA COMPUTER NETWORK/INFO 18.99 Pounds
        // Sterling"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        EDUCATION,
                        EDUCATION,
                        creditCardAccount,
                        "AplPay VIASAT, INC. CARLSBAD CA COMPUTER NETWORK/INFO 18.99 Pounds Sterling",
                        "AplPay VIASAT, INC. CARLSBAD CA COMPUTER NETWORK/INFO 18.99 Pounds Sterling",
                        BigDecimal.valueOf(-25.27),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be travel category (not education)
        assertNotNull(categoryResult);
        assertEquals(
                TRAVEL,
                categoryResult.getCategoryPrimary(),
                "VIASAT (satellite internet for travel) should be 'travel', not 'education'");
    }

    @Test
    void testAirlineFeeReimbursementTravelNotEducation() {
        // Given: Airline fee reimbursement (credit)
        // Transaction: "AMEX Airline Fee Reimbursement TRANSACTION PROCESSED BY AMERICAN EXPRESS"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other",
                        "other",
                        creditCardAccount,
                        "AGARWAL SUMIT KUMAR AMEX Airline Fee Reimbursement TRANSACTION PROCESSED BY AMERICAN EXPRESS",
                        "AGARWAL SUMIT KUMAR AMEX Airline Fee Reimbursement TRANSACTION PROCESSED BY AMERICAN EXPRESS",
                        BigDecimal.valueOf(80),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be travel category (not education)
        assertNotNull(categoryResult);
        assertEquals(
                TRAVEL,
                categoryResult.getCategoryPrimary(),
                "Airline fee reimbursement should be 'travel', not 'education'");
    }

    @Test
    void testPetSmartPetNotEducation() {
        // Given: PetSmart transaction
        // Transaction: "PETSMART # 0374 ISSAQUAH WA"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        PET,
                        PET,
                        creditCardAccount,
                        "PETSMART # 0374 ISSAQUAH WA",
                        "PETSMART # 0374 ISSAQUAH WA",
                        BigDecimal.valueOf(-113),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be pet category (not education)
        assertNotNull(categoryResult);
        assertEquals(
                PET,
                categoryResult.getCategoryPrimary(),
                "PetSmart should be 'pet', not 'education'");
    }

    @Test
    void testPetsBestInsurancePetNotEducation() {
        // Given: Pets Best Insurance transaction
        // Transaction: "PETS BEST INSURANCE SE ALTAMONTE SPR FL"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        PET,
                        PET,
                        creditCardAccount,
                        "PETS BEST INSURANCE SE ALTAMONTE SPR FL",
                        "PETS BEST INSURANCE SE ALTAMONTE SPR FL",
                        BigDecimal.valueOf(-523.73),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be pet category (not education)
        assertNotNull(categoryResult);
        assertEquals(
                PET,
                categoryResult.getCategoryPrimary(),
                "Pets Best Insurance should be 'pet', not 'education'");
    }

    @Test
    void testForeignTransactionFeeFeesNotEducation() {
        // Given: Foreign transaction fee
        // Transaction: "FOREIGN TRANSACTION FEE"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        EDUCATION,
                        EDUCATION,
                        creditCardAccount,
                        "FOREIGN TRANSACTION FEE",
                        "FOREIGN TRANSACTION FEE",
                        BigDecimal.valueOf(-0.4),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-0.4),
                        null,
                        "FOREIGN TRANSACTION FEE",
                        null);

        // Then: Should be fees category and EXPENSE type (not education/PAYMENT)
        assertNotNull(categoryResult);
        assertEquals(
                "fees",
                categoryResult.getCategoryPrimary(),
                "Foreign transaction fee should be 'fees', not 'education'");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Foreign transaction fee should be EXPENSE type, not PAYMENT");
    }

    @Test
    void testActMinimountainHealthNotEducation() {
        // Given: Ski rental (act*minimountain)
        // Transaction: "act*minimountain BELLEVUE WA"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        SHOPPING,
                        SHOPPING,
                        creditCardAccount,
                        "act*minimountain BELLEVUE WA",
                        "act*minimountain BELLEVUE WA",
                        BigDecimal.valueOf(-138.04),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        creditCardAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-138.04),
                        null,
                        "act*minimountain BELLEVUE WA",
                        null);

        // Then: Should be health category and EXPENSE type (not education/PAYMENT)
        assertNotNull(categoryResult);
        assertEquals(
                "health",
                categoryResult.getCategoryPrimary(),
                "act*minimountain (ski rental) should be 'health', not 'education'");
        assertNotNull(typeResult);
        assertEquals(
                TransactionType.EXPENSE,
                typeResult.getTransactionType(),
                "Ski rental should be EXPENSE type, not PAYMENT");
    }

    @Test
    void testRBLDiningNotEducation() {
        // Given: RBL* restaurant POS transaction
        // Transaction: "RBL*BOTTLE LAB TECHNOLOGIES BANGALORE BANGALORE KA"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        DINING,
                        DINING,
                        creditCardAccount,
                        "RBL*BOTTLE LAB TECHNOLOGIES BANGALORE BANGALORE KA edc.bankinvoices@[REDACTED] 104.00 Indian Rupees",
                        "RBL*BOTTLE LAB TECHNOLOGIES BANGALORE BANGALORE KA edc.bankinvoices@[REDACTED] 104.00 Indian Rupees",
                        BigDecimal.valueOf(-1.21),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be dining category (not education)
        assertNotNull(categoryResult);
        assertEquals(
                DINING,
                categoryResult.getCategoryPrimary(),
                "RBL* (restaurant POS) should be 'dining', not 'education'");
    }

    @Test
    void testEractollTransportationNotEducation() {
        // Given: Eractoll (toll payment)
        // Transaction: "ERACTOLL 7PK03R 877-860-1258 WA"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        EDUCATION,
                        EDUCATION,
                        creditCardAccount,
                        "ERACTOLL 7PK03R 877-860-1258 WA",
                        "ERACTOLL 7PK03R 877-860-1258 WA",
                        BigDecimal.valueOf(-7.95),
                        null,
                        null,
                        "PDF", null);

        // Then: Should be transportation category (not education)
        assertNotNull(categoryResult);
        assertEquals(
                TRANSPORTATION,
                categoryResult.getCategoryPrimary(),
                "Eractoll (toll) should be 'transportation', not 'education'");
    }

    @Test
    void testPayPalTransferTransferNotEducation() {
        // Given: PayPal transfer (expense from checking)
        // Transaction: "PAYPAL INST XFER GOYALANKITA0311 WEB ID: PAYPALSI77"
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "payment",
                        "payment",
                        checkingAccount,
                        "PAYPAL INST XFER GOYALANKITA0311 WEB ID: PAYPALSI77",
                        "PAYPAL INST XFER GOYALANKITA0311 WEB ID: PAYPALSI77",
                        BigDecimal.valueOf(-100),
                        null,
                        null,
                        "PDF", null);

        final TransactionTypeCategoryService.TypeResult typeResult =
                service.determineTransactionType(
                        checkingAccount,
                        categoryResult.getCategoryPrimary(),
                        categoryResult.getCategoryDetailed(),
                        BigDecimal.valueOf(-100),
                        null,
                        "PAYPAL INST XFER GOYALANKITA0311 WEB ID: PAYPALSI77",
                        null);

        // Then: Should be transfer category and EXPENSE type (not education)
        assertNotNull(categoryResult);
        assertEquals(
                "transfer",
                categoryResult.getCategoryPrimary(),
                "PayPal transfer should be 'transfer', not 'education'");
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    // ========== Override Validation Tests ==========
    // These tests verify we don't incorrectly override correct importer categories

    @Test
    void testDontOverrideCorrectPlaidCategory() {
        // Given: Correct Plaid category that should NOT be overridden
        // Transaction: "STARBUCKS #123 SEATTLE WA"
        // Importer (Plaid): DINING (correct)
        // Merchant detection might also find DINING or something else, but should trust Plaid
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        DINING, // Plaid correctly categorizes as dining
                        "restaurants", // Plaid detailed category
                        creditCardAccount,
                        "STARBUCKS #123 SEATTLE WA",
                        "STARBUCKS #123 SEATTLE WA",
                        BigDecimal.valueOf(-5.50),
                        null,
                        null,
                        "PLAID", // High-confidence Plaid source
                        null);

        // Then: Should trust Plaid category DINING (not override unless merchant confirms)
        assertNotNull(categoryResult);
        // Should be DINING - either from Plaid or merchant detection (both should agree)
        assertTrue(
                DINING.equals(categoryResult.getCategoryPrimary()),
                "Should trust or confirm Plaid 'dining' category for Starbucks, not override incorrectly");
    }

    @Test
    void testOverrideIncorrectGenericCategory() {
        // Given: Incorrect generic category that SHOULD be overridden
        // Transaction: "TST* DEEP DIVE SEATTLE WA" (restaurant)
        // Importer: UTILITIES (incorrect)
        // Merchant detection: DINING (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        UTILITIES, // Importer incorrectly categorizes as utilities
                        UTILITIES,
                        creditCardAccount,
                        "TST* DEEP DIVE SEATTLE WA",
                        "TST* DEEP DIVE SEATTLE WA",
                        BigDecimal.valueOf(-45.00),
                        null,
                        null,
                        "PDF", // Non-Plaid source, less reliable
                        null);

        // Then: Should override UTILITIES with DINING (merchant detection is clearly better)
        assertNotNull(categoryResult);
        assertEquals(
                DINING,
                categoryResult.getCategoryPrimary(),
                "Should override incorrect 'utilities' with correct 'dining' for restaurant transaction");
    }

    @Test
    void testDontOverrideCorrectSpecificCategory() {
        // Given: Correct specific category that should NOT be overridden incorrectly
        // Transaction: "WHOLE FOODS MARKET #456 SEATTLE WA"
        // Importer: GROCERIES (correct)
        // Merchant detection might find GROCERIES or SHOPPING, but should trust GROCERIES
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        GROCERIES, // Correct category
                        GROCERIES,
                        creditCardAccount,
                        "WHOLE FOODS MARKET #456 SEATTLE WA",
                        "WHOLE FOODS MARKET #456 SEATTLE WA",
                        BigDecimal.valueOf(-85.50),
                        null,
                        null,
                        "PLAID", // High-confidence Plaid source
                        null);

        // Then: Should trust or confirm GROCERIES (not override with SHOPPING or other)
        assertNotNull(categoryResult);
        assertEquals(
                GROCERIES,
                categoryResult.getCategoryPrimary(),
                "Should trust correct 'groceries' category for Whole Foods, not override with wrong category");
    }

    @Test
    void testOverrideGenericOtherCategory() {
        // Given: Generic "other" category that SHOULD be overridden
        // Transaction: "ESTEE LAUDER ONLINE MELVILLE" (shopping)
        // Importer: "other" (generic, unhelpful)
        // Merchant detection: SHOPPING (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        "other", // Generic category - should be overridden
                        "other",
                        creditCardAccount,
                        "GARIMA DIPTI AGARWAL ESTEE LAUDER ONLINE MELVILLE Amex Offer Credit",
                        "GARIMA DIPTI AGARWAL ESTEE LAUDER ONLINE MELVILLE Amex Offer Credit",
                        BigDecimal.valueOf(15),
                        null,
                        null,
                        "PDF", null);

        // Then: Should override "other" with SHOPPING (merchant detection is better)
        assertNotNull(categoryResult);
        assertEquals(
                SHOPPING,
                categoryResult.getCategoryPrimary(),
                "Should override generic 'other' with specific 'shopping' category for Estee Lauder");
    }

    @Test
    void testOverrideEducationForTransfer() {
        // Given: Incorrect EDUCATION category for transfer transaction
        // Transaction: "ONLINE TRANSFER TO CHK ...9994 TRANSACTION#: 12/19"
        // Importer: EDUCATION (incorrect - wrong category)
        // Merchant detection: "transfer" (correct)
        final TransactionTypeCategoryService.CategoryResult categoryResult =
                service.determineCategory(
                        EDUCATION, // Incorrect category
                        EDUCATION,
                        checkingAccount,
                        "ONLINE TRANSFER TO CHK ...9994 TRANSACTION#: 12/19",
                        "Online Transfer to CHK ...9994 transaction#: 27390930759 12/19",
                        BigDecimal.valueOf(-600),
                        null,
                        null,
                        "PDF", // Non-Plaid source
                        null);

        // Then: Should override EDUCATION with "transfer" (merchant detection is clearly correct)
        assertNotNull(categoryResult);
        assertEquals(
                "transfer",
                categoryResult.getCategoryPrimary(),
                "Should override incorrect 'education' with correct 'transfer' for transfer transaction");
    }
}
