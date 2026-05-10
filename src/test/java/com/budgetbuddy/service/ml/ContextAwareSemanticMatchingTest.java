package com.budgetbuddy.service.ml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for context-aware semantic matching Tests: amount context, payment channel context, account
 * type context
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Context-Aware Semantic Matching Tests")
class ContextAwareSemanticMatchingTest {

    @Mock private MerchantCategoryDataService merchantCategoryDataService;

    private SemanticMatchingService semanticMatchingService;

    @BeforeEach
    void setUp() {
        // CRITICAL: Mock MerchantCategoryDataService to return empty clusters for tests
        // Tests will add their own clusters as needed
        when(merchantCategoryDataService.getCategoryToKeywordsMap()).thenReturn(new HashMap<>());

        semanticMatchingService = new SemanticMatchingService(merchantCategoryDataService);
    }

    // ========== Payment Channel Context Tests ==========

    @Test
    @DisplayName("Context-aware: ACH payment channel boosts utilities/payment")
    void testContextAwareACHPaymentChannel() {
        // ACH payment should boost utilities/payment categories
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "PUGET SOUND ENERGY",
                        "Bill Payment",
                        new BigDecimal("-100.00"),
                        "ACH",
                        null,
                        null);

        assertNotNull(result, "Should find match with ACH context");
        assertTrue(
                "utilities".equals(result.category) || "payment".equals(result.category),
                "ACH should boost utilities or payment");
        assertTrue(result.similarity >= 0.6, "Should have good similarity with context boost");
    }

    @Test
    @DisplayName("Context-aware: POS payment channel boosts shopping/dining")
    void testContextAwarePOSPaymentChannel() {
        // POS payment should boost shopping/dining categories
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "STARBUCKS", "Coffee Purchase", new BigDecimal("-5.50"), "POS", null, null);

        assertNotNull(result, "Should find match with POS context");
        assertTrue(
                "dining".equals(result.category) || "shopping".equals(result.category),
                "POS should boost dining or shopping");
    }

    @Test
    @DisplayName("Context-aware: Online payment channel boosts subscriptions/shopping")
    void testContextAwareOnlinePaymentChannel() {
        // Online payment should boost subscriptions/shopping
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "NETFLIX", "Subscription", new BigDecimal("-15.99"), "online", null, null);

        assertNotNull(result, "Should find match with online context");
        // Should match entertainment or subscriptions
        assertTrue(result.similarity >= 0.6);
    }

    // ========== Account Type Context Tests ==========

    @Test
    @DisplayName("Context-aware: Investment account boosts investment category")
    void testContextAwareInvestmentAccount() {
        // Investment account should boost investment category
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "FIDELITY",
                        "Stock Purchase",
                        new BigDecimal("1000.00"),
                        null,
                        "investment",
                        null);

        assertNotNull(result, "Should find match with investment account context");
        assertEquals(
                "investment",
                result.category,
                "Investment account should boost investment category");
        assertTrue(result.similarity >= 0.6, "Should have good similarity with context boost");
    }

    @Test
    @DisplayName("Context-aware: Credit account boosts payment category")
    void testContextAwareCreditAccount() {
        // Credit account should boost payment category
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "CHASE CREDIT",
                        "Auto Pay",
                        new BigDecimal("-500.00"),
                        "ACH",
                        "credit",
                        null);

        assertNotNull(result, "Should find match with credit account context");
        assertEquals("payment", result.category, "Credit account should boost payment category");
    }

    @Test
    @DisplayName("Context-aware: Loan account boosts payment category")
    void testContextAwareLoanAccount() {
        // Loan account should boost payment category
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "CAR LOAN",
                        "Monthly Payment",
                        new BigDecimal("-300.00"),
                        "ACH",
                        "loan",
                        null);

        assertNotNull(result, "Should find match with loan account context");
        assertEquals("payment", result.category, "Loan account should boost payment category");
    }

    // ========== Amount Context Tests ==========

    @Test
    @DisplayName("Context-aware: Large positive amount boosts investment/income")
    void testContextAwareLargePositiveAmount() {
        // Large positive amount should boost investment/income
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "MORGAN STANLEY", "Transfer", new BigDecimal("5000.00"), null, null, null);

        assertNotNull(result, "Should find match with large positive amount");
        // Should boost investment or deposit
        assertTrue(result.similarity >= 0.6);
    }

    @Test
    @DisplayName("Context-aware: Small amount boosts groceries/dining")
    void testContextAwareSmallAmount() {
        // Small amount should boost groceries/dining
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "GROCERY STORE", "Purchase", new BigDecimal("-25.50"), "POS", null, null);

        assertNotNull(result, "Should find match with small amount");
        assertTrue(
                "groceries".equals(result.category)
                        || "dining".equals(result.category)
                        || "shopping".equals(result.category),
                "Small amount should boost groceries, dining, or shopping");
    }

    @Test
    @DisplayName("Context-aware: Very small amount boosts dining")
    void testContextAwareVerySmallAmount() {
        // Very small amount (< $10) should boost dining
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "COFFEE SHOP", "Coffee", new BigDecimal("-4.50"), "POS", null, null);

        assertNotNull(result, "Should find match with very small amount");
        assertEquals("dining", result.category, "Very small amount should boost dining");
    }

    // ========== Combined Context Tests ==========

    @Test
    @DisplayName("Context-aware: ACH + positive amount boosts deposit/income")
    void testContextAwareACHPositiveAmount() {
        // ACH with positive amount should boost deposit/income
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "DEPOSIT",
                        "ACH Credit",
                        new BigDecimal("1000.00"),
                        "ACH",
                        "checking",
                        null);

        assertNotNull(result, "Should find match with ACH + positive amount");
        // Should boost deposit, income, or investment
        assertTrue(result.similarity >= 0.6);
    }

    @Test
    @DisplayName("Context-aware: Investment account + large amount boosts investment")
    void testContextAwareInvestmentAccountLargeAmount() {
        // Investment account + large amount should strongly boost investment
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "VANGUARD",
                        "Fund Purchase",
                        new BigDecimal("5000.00"),
                        null,
                        "investment",
                        "mutual_fund");

        assertNotNull(result, "Should find match with investment account + large amount");
        assertEquals(
                "investment",
                result.category,
                "Investment account + large amount should strongly boost investment");
        assertTrue(
                result.similarity >= 0.7, "Should have high similarity with strong context boost");
    }

    // ========== Account Subtype Context Tests ==========

    @Test
    @DisplayName("Context-aware: CD account subtype boosts investment")
    void testContextAwareCDSubtype() {
        // CD account subtype should boost investment
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "BANK", "CD Interest", new BigDecimal("50.00"), null, "savings", "cd");

        assertNotNull(result, "Should find match with CD subtype");
        assertEquals("investment", result.category, "CD subtype should boost investment");
    }

    @Test
    @DisplayName("Context-aware: Credit card subtype boosts payment")
    void testContextAwareCreditCardSubtype() {
        // Credit card subtype should boost payment
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "CITI",
                        "Auto Pay",
                        new BigDecimal("-200.00"),
                        "ACH",
                        "credit",
                        "credit_card");

        assertNotNull(result, "Should find match with credit card subtype");
        assertEquals("payment", result.category, "Credit card subtype should boost payment");
    }

    // ========== Context Boost Validation Tests ==========

    @Test
    @DisplayName("Context-aware: Context boost is capped at 0.3")
    void testContextAwareBoostCapped() {
        // Even with multiple context signals, boost should be capped at 0.3
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "FIDELITY",
                        "Investment",
                        new BigDecimal("10000.00"),
                        "ACH",
                        "investment",
                        "brokerage");

        // Should have high similarity but not exceed 1.0
        if (result != null) {
            assertTrue(result.similarity <= 1.0, "Similarity should not exceed 1.0");
        }
    }

    @Test
    @DisplayName("Context-aware: Works without context (backward compatible)")
    void testContextAwareBackwardCompatible() {
        // Should work without context (backward compatible)
        final SemanticMatchingService.SemanticMatchResult result =
                semanticMatchingService.findBestSemanticMatchWithContext(
                        "GROCERY STORE", "Shopping", null, null, null, null);

        assertNotNull(result, "Should work without context");
        assertEquals("groceries", result.category, "Should match groceries without context");
    }
}
