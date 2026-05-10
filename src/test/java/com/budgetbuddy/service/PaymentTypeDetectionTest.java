package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for payment type detection in PlaidCategoryMapper */
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
@ExtendWith(MockitoExtension.class)
class PaymentTypeDetectionTest {

    private static final String PAYMENT = "payment";

    @InjectMocks private PlaidCategoryMapper categoryMapper;

    @Test
    void testDetectCreditCardPaymentWithCreditCardPaymentDescription() {
        // Given
        final String description = "Credit Card Payment - Chase";
        final String merchantName = null;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(PAYMENT, mapping.getPrimary());
        assertEquals(PAYMENT, mapping.getDetailed());
    }

    @Test
    void testDetectCreditCardPaymentWithCCPayment() {
        // Given
        final String description = "CC Payment";
        final String merchantName = null;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-300.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(PAYMENT, mapping.getPrimary());
    }

    @Test
    void testDetectRecurringACHPaymentWithRecurringKeyword() {
        // Given
        final String description = "Monthly Recurring Payment - Utilities";
        final String merchantName = "Utility Company";
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("-100.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(PAYMENT, mapping.getPrimary());
        assertEquals(PAYMENT, mapping.getDetailed());
    }

    @Test
    void testDetectRecurringACHPaymentWithAutopay() {
        // Given
        final String description = "AUTOPAY - Loan Payment";
        final String merchantName = "Loan Company";
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("-250.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(PAYMENT, mapping.getPrimary());
    }

    @Test
    void testDetectRecurringACHPaymentWithBillPay() {
        // Given
        final String description = "Bill Pay - Credit Card";
        final String merchantName = null;
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("-200.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(PAYMENT, mapping.getPrimary());
    }

    @Test
    void testDetectRecurringACHPaymentWithNonACHReturnsOther() {
        // Given: Non-ACH transaction
        final String description = "Online Purchase";
        final String merchantName = "Amazon";
        final String paymentChannel = "online";
        final BigDecimal amount = new BigDecimal("-50.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then: Should not be payment (no payment channel or keywords)
        assertNotNull(mapping);
        assertNotEquals(PAYMENT, mapping.getPrimary());
    }

    @Test
    void testDetectRecurringACHPaymentWithPositiveAmountNotPayment() {
        // Given: Positive ACH (credit, not payment)
        final String description = "ACH Credit";
        final String merchantName = null;
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then: Should be income, not payment
        assertNotNull(mapping);
        assertNotEquals(PAYMENT, mapping.getPrimary());
        assertEquals("income", mapping.getPrimary()); // ACH credit should be income
    }

    @Test
    void testPaymentDetectionPriorityOverOtherCategories() {
        // Given: Credit card payment that might be categorized as other
        final String description = "Credit Card Payment";
        final String merchantName = null;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "GENERAL_SERVICES",
                        "GENERAL_SERVICES",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then: Payment should override other categories
        assertNotNull(mapping);
        assertEquals(PAYMENT, mapping.getPrimary());
    }
}
