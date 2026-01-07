package com.budgetbuddy.service;

import com.budgetbuddy.model.TransactionType;
import com.budgetbuddy.model.dynamodb.AccountTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import com.budgetbuddy.AWSTestConfiguration;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for transaction categorization issues reported by user
 * Tests all the specific transaction categorization fixes
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class TransactionCategorizationIssuesTest {

    @Autowired
    private TransactionTypeCategoryService service;

    @Autowired
    private CSVImportService csvImportService;

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
    void testWellsFargoCreditCardPayment_PositiveAmount() {
        // Given: Wells Fargo credit card payment with negative amount (should be converted to positive)
        // "WF Credit Card   AUTO PAY                   PPD ID: 50260000" with amount -447.54
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "payment",
            "payment",
            wellsFargoCreditCardAccount,
            "WF CREDIT CARD AUTO PAY PPD ID:",
            "WF Credit Card   AUTO PAY                   PPD ID: 50260000",
            BigDecimal.valueOf(-447.54),
            null,
            null,
            "CSV"
        );

        TransactionTypeCategoryService.TypeResult typeResult = service.determineTransactionType(
            wellsFargoCreditCardAccount,
            categoryResult.getCategoryPrimary(),
            categoryResult.getCategoryDetailed(),
            BigDecimal.valueOf(-447.54),
            null,
            "WF Credit Card   AUTO PAY                   PPD ID: 50260000",
            null
        );

        // Then: Should be payment category and PAYMENT type
        assertNotNull(categoryResult);
        assertEquals("payment", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.PAYMENT, typeResult.getTransactionType());
    }

    // ========== Subscription Refund Tests ==========
    
    @Test
    void testSubscriptionRefund_Barrons() {
        // Given: BARRONS subscription refund (positive amount on credit card)
        // "Platinum Digital Entertainment Credit D J*BARRONS" with amount 4.41
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "subscriptions",
            "subscriptions",
            creditCardAccount,
            "Platinum Digital Entertainment Credit D J*BARRONS",
            "Platinum Digital Entertainment Credit D J*BARRONS",
            BigDecimal.valueOf(4.41),
            null,
            null,
            "PDF"
        );

        // Then: Should be subscriptions, not credit
        assertNotNull(categoryResult);
        assertEquals("subscriptions", categoryResult.getCategoryPrimary());
    }

    @Test
    void testSubscriptionRefund_WalmartPlus() {
        // Given: Walmart+ subscription refund (positive amount on credit card)
        // "Platinum Walmart+ Credit WMT PLUS Jun 2025 02737" with amount 14.27
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "groceries",
            "groceries",
            creditCardAccount,
            "Platinum Walmart+ Credit WMT PLUS Jun 2025 02737",
            "Platinum Walmart+ Credit WMT PLUS Jun 2025 02737",
            BigDecimal.valueOf(14.27),
            null,
            null,
            "PDF"
        );

        // Then: Should be groceries, not credit
        assertNotNull(categoryResult);
        assertEquals("groceries", categoryResult.getCategoryPrimary());
    }

    @Test
    void testGroceriesRefund_Costco() {
        // Given: Costco refund (positive amount on credit card)
        // "WWW COSTCO COM 800-955-2292 WA" with amount 117.17
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "groceries",
            "groceries",
            creditCardAccount,
            "WWW COSTCO COM 800-955-2292 WA",
            "WWW COSTCO COM 800-955-2292 WA",
            BigDecimal.valueOf(117.17),
            null,
            null,
            "PDF"
        );

        // Then: Should be groceries, not credit
        assertNotNull(categoryResult);
        assertEquals("groceries", categoryResult.getCategoryPrimary());
    }

    // ========== Education Category Tests ==========
    
    @Test
    void testEducation_BellevueSchoolDistrict() {
        // Given: Bellevue School District transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "other",
            "other",
            checkingAccount,
            "BELLEVUE SCHOOL DISTRI BELLEVUE WA",
            "BELLEVUE SCHOOL DISTRI BELLEVUE WA",
            BigDecimal.valueOf(-101),
            null,
            null,
            "PDF"
        );

        TransactionTypeCategoryService.TypeResult typeResult = service.determineTransactionType(
            checkingAccount,
            categoryResult.getCategoryPrimary(),
            categoryResult.getCategoryDetailed(),
            BigDecimal.valueOf(-101),
            null,
            "BELLEVUE SCHOOL DISTRI BELLEVUE WA",
            null
        );

        // Then: Should be education, not other
        assertNotNull(categoryResult);
        assertEquals("education", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    @Test
    void testEducation_Anki() {
        // Given: Anki transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "other",
            "other",
            creditCardAccount,
            "SP ANKI REMOTE NEWBURGH IN +17864744370",
            "SP ANKI REMOTE NEWBURGH IN +17864744370",
            BigDecimal.valueOf(-51.03),
            null,
            null,
            "PDF"
        );

        // Then: Should be education, not other
        assertNotNull(categoryResult);
        assertEquals("education", categoryResult.getCategoryPrimary());
    }

    @Test
    void testEducation_AAMCExam() {
        // Given: AAMC exam transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "entertainment",
            "entertainment",
            creditCardAccount,
            "MUDIT AGARWAL VUE*AAMC EXAM BLOOMINGTON 0074-7349-7152|26088021|1",
            "MUDIT AGARWAL VUE*AAMC EXAM BLOOMINGTON 0074-7349-7152|26088021|1",
            BigDecimal.valueOf(170),
            null,
            null,
            "PDF"
        );

        // Then: Should be education, not entertainment
        assertNotNull(categoryResult);
        assertEquals("education", categoryResult.getCategoryPrimary());
    }

    @Test
    void testEducation_Gurukul() {
        // Given: Gurukul (Indian school) transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "other",
            "other",
            creditCardAccount,
            "PAYPAL *GURUKUL GURUKUL W 0000000000 WA 0000000000",
            "PAYPAL *GURUKUL GURUKUL W 0000000000 WA 0000000000",
            BigDecimal.valueOf(-400),
            null,
            null,
            "PDF"
        );

        // Then: Should be education, not other
        assertNotNull(categoryResult);
        assertEquals("education", categoryResult.getCategoryPrimary());
    }

    @Test
    void testEducation_TyeeMiddleSchool() {
        // Given: Tyee Middle School transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "charity",
            "charity",
            checkingAccount,
            "TYEE MIDDLE SCHOOL PTS BELLEVUE WA",
            "TYEE MIDDLE SCHOOL PTS BELLEVUE WA",
            BigDecimal.valueOf(-100),
            null,
            null,
            "PDF"
        );

        // Then: Should be education, not charity
        assertNotNull(categoryResult);
        assertEquals("education", categoryResult.getCategoryPrimary());
    }

    @Test
    void testEducation_UniversityBookStore() {
        // Given: University Book Store transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",
            "utilities",
            checkingAccount,
            "UNIVERSITY BOOK STORE, SEATTLE WA",
            "UNIVERSITY BOOK STORE, SEATTLE WA",
            BigDecimal.valueOf(-66.24),
            null,
            null,
            "PDF"
        );

        // Then: Should be education, not utilities
        assertNotNull(categoryResult);
        assertEquals("education", categoryResult.getCategoryPrimary());
    }

    // ========== Travel Category Tests ==========
    
    @Test
    void testTravel_CenturionLounge() {
        // Given: Centurion Lounge transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",
            "utilities",
            creditCardAccount,
            "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER",
            "AXP CENTURION LOUNGE 3067 SEATTLE WA 3228 98158 OTHER",
            BigDecimal.valueOf(-30),
            null,
            null,
            "PDF"
        );

        // Then: Should be travel, not utilities
        assertNotNull(categoryResult);
        assertEquals("travel", categoryResult.getCategoryPrimary());
    }

    // ========== Transportation Category Tests ==========
    
    @Test
    void testTransportation_Lyft() {
        // Given: Lyft ride transaction (not subscription)
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "subscriptions",
            "subscriptions",
            creditCardAccount,
            "LYFT *RIDE FRI 5PM LYFT.COM CA",
            "LYFT *RIDE FRI 5PM LYFT.COM CA",
            BigDecimal.valueOf(-67.7),
            null,
            null,
            "PDF"
        );

        // Then: Should be transportation, not subscriptions
        assertNotNull(categoryResult);
        assertEquals("transportation", categoryResult.getCategoryPrimary());
    }

    @Test
    void testTransportation_Exxon() {
        // Given: Exxon gas station transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "subscriptions",
            "subscriptions",
            creditCardAccount,
            "EXXON ZOOMERZ #967 KINGSTON TN",
            "EXXON ZOOMERZ #967 KINGSTON TN",
            BigDecimal.valueOf(-17.68),
            null,
            null,
            "PDF"
        );

        // Then: Should be transportation, not subscriptions
        assertNotNull(categoryResult);
        assertEquals("transportation", categoryResult.getCategoryPrimary());
    }

    @Test
    void testTransportation_PayByPhone() {
        // Given: Pay by Phone parking transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",
            "utilities",
            creditCardAccount,
            "UW PAY BY PHONE SEATTLE WA 206-685-1553",
            "UW PAY BY PHONE SEATTLE WA 206-685-1553",
            BigDecimal.valueOf(-21),
            null,
            null,
            "PDF"
        );

        // Then: Should be transportation, not utilities
        assertNotNull(categoryResult);
        assertEquals("transportation", categoryResult.getCategoryPrimary());
    }

    // ========== Dining Category Tests ==========
    
    @Test
    void testDining_TSTDeepDive() {
        // Given: TST* DEEP DIVE transaction (Toast POS system)
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",
            "utilities",
            creditCardAccount,
            "TST* DEEP DIVE SEATTLE WA",
            "TST* DEEP DIVE SEATTLE WA",
            BigDecimal.valueOf(-50),
            null,
            null,
            "PDF"
        );

        TransactionTypeCategoryService.TypeResult typeResult = service.determineTransactionType(
            creditCardAccount,
            categoryResult.getCategoryPrimary(),
            categoryResult.getCategoryDetailed(),
            BigDecimal.valueOf(-50),
            null,
            "TST* DEEP DIVE SEATTLE WA",
            null
        );

        // Then: Should be dining, not utilities, and EXPENSE, not LOAN
        assertNotNull(categoryResult);
        assertEquals("dining", categoryResult.getCategoryPrimary());
        assertNotNull(typeResult);
        assertEquals(TransactionType.EXPENSE, typeResult.getTransactionType());
    }

    @Test
    void testDining_TPD() {
        // Given: TPD (Top Pot Donuts) transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",
            "utilities",
            checkingAccount,
            "TPD 5TH AVE 102 SEATTLE WA",
            "TPD 5TH AVE 102 SEATTLE WA",
            BigDecimal.valueOf(-54.89),
            null,
            null,
            "PDF"
        );

        // Then: Should be dining, not utilities
        assertNotNull(categoryResult);
        assertEquals("dining", categoryResult.getCategoryPrimary());
    }

    @Test
    void testDining_SQSunnyHoney() {
        // Given: SQ* (Square POS) transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",
            "utilities",
            checkingAccount,
            "SQ *SUNNY HONEY COMPAN Seattle WA",
            "SQ *SUNNY HONEY COMPAN Seattle WA",
            BigDecimal.valueOf(-5),
            null,
            null,
            "PDF"
        );

        // Then: Should be dining, not utilities
        assertNotNull(categoryResult);
        assertEquals("dining", categoryResult.getCategoryPrimary());
    }

    @Test
    void testDining_BurgerAndKabobHut() {
        // Given: Burger and Kabob Hut transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",
            "utilities",
            checkingAccount,
            "BURGER AND KABOB HUT SEATTLE WA",
            "BURGER AND KABOB HUT SEATTLE WA",
            BigDecimal.valueOf(-57.46),
            null,
            null,
            "PDF"
        );

        // Then: Should be dining, not utilities
        assertNotNull(categoryResult);
        assertEquals("dining", categoryResult.getCategoryPrimary());
    }

    // ========== Health Category Tests ==========
    
    @Test
    void testHealth_BadmintonClub() {
        // Given: Seattle Badminton Club transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "utilities",
            "utilities",
            checkingAccount,
            "SEATTLE BADMINTON CLUB KIRKLAND WA",
            "SEATTLE BADMINTON CLUB KIRKLAND WA",
            BigDecimal.valueOf(-22.06),
            null,
            null,
            "PDF"
        );

        // Then: Should be health, not utilities
        assertNotNull(categoryResult);
        assertEquals("health", categoryResult.getCategoryPrimary());
    }

    // ========== Pet Category Tests ==========
    
    @Test
    void testPet_PetcareClinic() {
        // Given: Petcare Clinic transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "healthcare",
            "healthcare",
            checkingAccount,
            "PETCARE CLINIC BELLEVU BELLEVUE WA",
            "PETCARE CLINIC BELLEVU BELLEVUE WA",
            BigDecimal.valueOf(-113.92),
            null,
            null,
            "PDF"
        );

        // Then: Should be pet, not healthcare
        assertNotNull(categoryResult);
        assertEquals("pet", categoryResult.getCategoryPrimary());
    }

    // ========== Groceries Category Tests ==========
    
    @Test
    void testGroceries_FredMeyer() {
        // Given: Fred Meyer transaction
        TransactionTypeCategoryService.CategoryResult categoryResult = service.determineCategory(
            "other",
            "other",
            checkingAccount,
            "FRED-MEYER #0658 ISSAQUAH WA",
            "FRED-MEYER #0658 ISSAQUAH WA",
            BigDecimal.valueOf(-6),
            null,
            null,
            "PDF"
        );

        // Then: Should be groceries, not other
        assertNotNull(categoryResult);
        assertEquals("groceries", categoryResult.getCategoryPrimary());
    }
}

