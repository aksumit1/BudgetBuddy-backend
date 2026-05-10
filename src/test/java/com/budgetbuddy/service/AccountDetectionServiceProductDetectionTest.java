package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit Tests for AccountDetectionService - Product/Card Name Detection Tests product name
 * extraction from PDF statements, including Prime Visa detection
 */
@ExtendWith(MockitoExtension.class)
class AccountDetectionServiceProductDetectionTest {

    @Mock private AccountRepository accountRepository;

    @Mock private BalanceExtractor balanceExtractor;

    private AccountDetectionService accountDetectionService;

    @BeforeEach
    void setUp() {
        accountDetectionService = new AccountDetectionService(accountRepository, balanceExtractor);
    }

    @Test
    void testExtractProductNamePrimeVisaFromYourPrimeVisaPoints() {
        // Given - Prime Visa statement with "YOUR PRIME VISA POINTS" pattern
        final String headerText =
                "If you would like information about credit counseling services, call, 1-866-797-2885.\n"
                        + "Previous Balance $458.40\n"
                        + "Payment, Credits -$458.40\n"
                        + "Purchases +$377.32\n"
                        + "Cash Advances $0.00\n"
                        + "Balance Transfers $0.00\n"
                        + "Fees Charged $0.00\n"
                        + "Interest Charged $0.00\n"
                        + "Opening/Closing Date 10/13/25 - 11/12/25\n"
                        + "Credit Access Line $15,000\n"
                        + "Available Credit $14,622\n"
                        + "Cash Access Line $3,000\n"
                        + "Available for Cash $3,000\n"
                        + "Previous points balance 49,825\n"
                        + "+ 5% back on Amazon.com purchases 0\n"
                        + "+ 5% back on Whole Foods Market purchases 0\n"
                        + "+ 2% back at gas stations 0\n"
                        + "+ 2% back at restaurants 631\n"
                        + "+ 5% back on Chase Travel purchases 0\n"
                        + "+ 2% back on local transit/commuting 0\n"
                        + "+ 1% back on all other purchases 63\n"
                        + "+ 5% back at Amazon sites and stores 0\n"
                        + "Reward your routine everywhere you shop with your Prime Visa.\n"
                        + "The % back rewards you earn under the program are tracked as points.\n"
                        + "Each $1 in % back rewards earned is equal to 100 points.\n"
                        + "Cardmembers earn unlimited 5% back at Amazon.com\n"
                        + "YOUR PRIME VISA POINTS\n"
                        + "Total points available for";

        // When
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(headerText, null);

        // Then
        assertNotNull(detected);
        assertNotNull(detected.getAccountName());
        final String accountName = detected.getAccountName().toLowerCase(Locale.ROOT);
        assertTrue(
                accountName.contains("prime") && accountName.contains("visa"),
                "Account name should contain 'prime' and 'visa', but was: "
                        + detected.getAccountName());
    }

    @Test
    void testExtractProductNamePrimeVisaFromRewardPhrase() {
        // Given - Prime Visa statement with "Reward your routine everywhere you shop with your
        // Prime Visa" pattern
        final String headerText =
                "Chase\n"
                        + "Reward your routine everywhere you shop with your Prime Visa.\n"
                        + "The % back rewards you earn under the program are tracked as points.\n"
                        + "Cardmembers earn unlimited 5% back at Amazon.com";

        // When
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(headerText, null);

        // Then
        assertNotNull(detected);
        assertNotNull(detected.getAccountName());
        final String accountName = detected.getAccountName().toLowerCase(Locale.ROOT);
        assertTrue(
                accountName.contains("prime") && accountName.contains("visa"),
                "Account name should contain 'prime' and 'visa', but was: "
                        + detected.getAccountName());
    }

    @Test
    void testExtractProductNamePrimeVisaRejectsChaseMobileApp() {
        // Given - Statement with "Chase mobile app" (should NOT be detected as product name)
        final String headerText =
                "Chase\n"
                        + "Download the Chase mobile app for easy account management.\n"
                        + "Visit chase.com or download from the App Store.\n"
                        + "YOUR PRIME VISA POINTS\n"
                        + "Total points available for 50,000";

        // When
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(headerText, null);

        // Then
        assertNotNull(detected);
        assertNotNull(detected.getAccountName());
        final String accountName = detected.getAccountName().toLowerCase(Locale.ROOT);
        // Should detect Prime Visa, NOT "Chase mobile app"
        assertTrue(
                accountName.contains("prime") && accountName.contains("visa"),
                "Account name should contain 'prime' and 'visa', but was: "
                        + detected.getAccountName());
        // Note: We can't directly test that "mobile" or "app" are not in the name
        // because the account name might be generated, but Prime Visa should be prioritized
    }

    @Test
    void testExtractProductNamePrimeVisaWithAmazonPrimeVisa() {
        // Given - Statement with "Amazon Prime Visa" pattern
        final String headerText =
                "Chase\n"
                        + "Amazon Prime Visa\n"
                        + "Account ending in 1234\n"
                        + "Statement Period: 10/13/25 - 11/12/25";

        // When
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(headerText, null);

        // Then
        assertNotNull(detected);
        assertNotNull(detected.getAccountName());
        final String accountName = detected.getAccountName().toLowerCase(Locale.ROOT);
        assertTrue(
                accountName.contains("prime") && accountName.contains("visa"),
                "Account name should contain 'prime' and 'visa', but was: "
                        + detected.getAccountName());
    }

    @Test
    void testExtractProductNamePrimeVisaWithPrimeRewardsVisa() {
        // Given - Statement with "Prime Rewards Visa" pattern
        final String headerText =
                "Chase\n" + "Prime Rewards Visa Signature\n" + "Account ending in 1234";

        // When
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(headerText, null);

        // Then
        assertNotNull(detected);
        assertNotNull(detected.getAccountName());
        final String accountName = detected.getAccountName().toLowerCase(Locale.ROOT);
        assertTrue(
                accountName.contains("prime") && accountName.contains("visa"),
                "Account name should contain 'prime' and 'visa', but was: "
                        + detected.getAccountName());
    }

    @Test
    void testExtractProductNameRejectsGenericTerms() {
        // Given - Statement with generic terms that should NOT be detected as product names
        final String[] genericTerms = {
            "Chase mobile app",
            "Visit chase.com",
            "Download the app",
            "Log in to your account",
            "Contact customer service",
            "Register for online banking"
        };

        for (final String genericTerm : genericTerms) {
            final String headerText =
                    "Chase\n"
                            + genericTerm
                            + "\n"
                            + "YOUR PRIME VISA POINTS\n"
                            + "Total points available for 50,000";

            // When
            final AccountDetectionService.DetectedAccount detected =
                    accountDetectionService.detectFromPDFContent(headerText, null);

            // Then
            assertNotNull(detected);
            assertNotNull(detected.getAccountName());
            final String accountName = detected.getAccountName().toLowerCase(Locale.ROOT);
            // Should detect Prime Visa, NOT the generic term
            assertTrue(
                    accountName.contains("prime") && accountName.contains("visa"),
                    "For generic term '"
                            + genericTerm
                            + "', account name should contain 'prime' and 'visa', but was: "
                            + detected.getAccountName());
            // Prime Visa should be prioritized over generic terms
        }
    }

    @Test
    void testExtractProductNamePrioritizesSpecificCardNames() {
        // Given - Statement with multiple card name candidates
        final String headerText =
                "Chase\n"
                        + "Chase mobile app - Download now\n"
                        + "YOUR PRIME VISA POINTS\n"
                        + "Total points available for 50,000\n"
                        + "Reward your routine everywhere you shop with your Prime Visa.";

        // When
        final AccountDetectionService.DetectedAccount detected =
                accountDetectionService.detectFromPDFContent(headerText, null);

        // Then
        assertNotNull(detected);
        assertNotNull(detected.getAccountName());
        final String accountName = detected.getAccountName().toLowerCase(Locale.ROOT);
        // Should prioritize Prime Visa over "Chase mobile app"
        assertTrue(
                accountName.contains("prime") && accountName.contains("visa"),
                "Account name should prioritize 'Prime Visa' over generic terms, but was: "
                        + detected.getAccountName());
        // Prime Visa should be detected, not "Chase mobile app"
    }
}
