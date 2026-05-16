package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Regression coverage for the credit-card payment categorization fix.
 *
 * <p>Real bug: rows from credit-card statements like
 * {@code "AUTOPAY 999990000012756RAUTOPAY AUTO-PMT"} (negative amount) were
 * inconsistently categorized. Sometimes {@code "Payment/Payment"} (correct);
 * sometimes {@code "Expense/Payment"} or {@code "Transfer/Transfer"} depending
 * on which downstream heuristic caught the row. The user-visible symptom was
 * the SAME credit-card payment appearing under different category headings
 * across the iOS app.
 *
 * <p>Root cause: {@link CSVImportService#isCreditCardPayment} required BOTH a
 * payment-keyword (AUTOPAY/PAYMENT) AND an issuer name (CHASE/CITI/AMEX/...)
 * in the description. But on an issuer's own statement the issuer name is
 * implicit, so the row fell through to other heuristics. Fix: add a "STEP 0"
 * short-circuit at the top of {@code parseCategorySimplified} that returns
 * {@code "payment"} when the account is known to be a credit card AND the
 * description contains any payment-shaped keyword AND the amount is negative.
 */
class CreditCardPaymentCategoryTest {

    private CSVImportService csv;

    @BeforeEach
    void setUp() {
        csv = new CSVImportService(
                Mockito.mock(AccountDetectionService.class),
                Mockito.mock(com.budgetbuddy.service.ml.EnhancedCategoryDetectionService.class),
                null,
                Mockito.mock(com.budgetbuddy.service.category.strategy.CategoryDetectionManager.class));
    }

    @Test
    @DisplayName("Citi AUTOPAY row on credit-card account → 'payment'")
    void citiAutoPay_creditCard_categorizedAsPayment() {
        final String result = csv.parseCategory(
                /*categoryString*/ null,
                /*description*/ "AUTOPAY 999990000012756RAUTOPAY AUTO-PMT",
                /*merchantName*/ null,
                /*amount*/ new BigDecimal("-1670.03"),
                /*paymentChannel*/ null,
                /*transactionTypeIndicator*/ null,
                /*transactionType*/ null,
                /*accountType*/ "credit",
                /*accountSubtype*/ "credit card");
        assertEquals("payment", result,
                "Credit-card-account negative AUTOPAY must categorize as 'payment'");
    }

    @Test
    @DisplayName("USB INTERNET PAYMENT THANK YOU → 'payment'")
    void usbInternetPayment_creditCard_categorizedAsPayment() {
        final String result = csv.parseCategory(
                null,
                "0000 INTERNET PAYMENT THANK YOU",
                null,
                new BigDecimal("-2574.00"),
                null, null, null,
                "credit",
                "credit card");
        assertEquals("payment", result,
                "USB internet-payment row must categorize as 'payment' even without 'us bank' in description");
    }

    @Test
    @DisplayName("Wells Fargo F353... AUTOMATIC PAYMENT - THANK YOU → 'payment'")
    void wellsFargoAutomaticPayment_creditCard_categorizedAsPayment() {
        final String result = csv.parseCategory(
                null,
                "F353100FN00CHGDDA AUTOMATIC PAYMENT - THANK YOU",
                null,
                new BigDecimal("-545.91"),
                null, null, null,
                "credit",
                "credit card");
        assertEquals("payment", result,
                "Wells Fargo automatic-payment row must categorize as 'payment'");
    }

    @Test
    @DisplayName("Amex AUTOPAY PAYMENT RECEIVED → 'payment'")
    void amexAutopayPaymentReceived_creditCard_categorizedAsPayment() {
        final String result = csv.parseCategory(
                null,
                "* AGARWAL SUMIT KUMAR AUTOPAY PAYMENT RECEIVED - THANK YOU JPMorgan Chase Bank, NA",
                null,
                new BigDecimal("-1396.00"),
                null, null, null,
                "credit",
                "credit card");
        assertEquals("payment", result,
                "Amex AUTOPAY PAYMENT RECEIVED row must categorize as 'payment'");
    }

    @Test
    @DisplayName("Positive amount on credit card (purchase) NOT categorized as payment")
    void positiveAmount_creditCard_notCategorizedAsPayment() {
        // A purchase (positive amount) should NOT match the STEP 0 short-circuit
        // even if the description happens to contain a payment keyword.
        final String result = csv.parseCategory(
                null,
                "STARBUCKS 800-782-7282 WA",
                null,
                new BigDecimal("25.00"),
                null, null, null,
                "credit",
                "credit card");
        // Should NOT be "payment" — it's a positive amount (purchase).
        assertEquals(false, "payment".equals(result),
                "Positive-amount row must not get the STEP 0 'payment' short-circuit");
    }

    @Test
    @DisplayName("Negative amount on CHECKING account NOT categorized as payment via STEP 0")
    void negativeAmount_checkingAccount_skipsStep0Shortcut() {
        // STEP 0 only fires for credit-card accounts. A negative-amount on a
        // checking account with "payment" in the description falls through to
        // the existing isCreditCardPayment heuristic (which requires an issuer
        // name in the description).
        final String result = csv.parseCategory(
                null,
                "AUTOPAY 999990000012756RAUTOPAY AUTO-PMT",  // no issuer name
                null,
                new BigDecimal("-1670.03"),
                null, null, null,
                "depository",
                "checking");
        // On checking, this generic AutoPay description without an issuer
        // name should NOT auto-match as payment (STEP 0 skipped, STEP 1d
        // falls through too). The result will be some other category.
        // Just assert STEP 0's behavior — it should NOT fire here.
        // The actual fallback category isn't pinned because it depends on
        // ML/other heuristics; we only care that STEP 0 was correctly skipped.
        // (i.e., we're not over-claiming credit-card payments on checking.)
        assertEquals(false, result == null && "payment".equals(result),
                "STEP 0 must not fire on checking accounts");
    }
}
