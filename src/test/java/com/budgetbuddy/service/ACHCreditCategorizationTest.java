package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for ACH credit categorization fixes - ACH credits (positive amounts) should be income, not
 * rent/expense
 */
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
@ExtendWith(MockitoExtension.class)
class ACHCreditCategorizationTest {

    @InjectMocks private PlaidCategoryMapper categoryMapper;

    @Test
    void testACHCreditCategorizedAsIncome() {
        // Given: ACH credit transaction without specific salary indicators
        final String description = "ACH Credit";
        final String merchantName = null;
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "RENT",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH credit should be income, not rent");
        // CRITICAL: ACH credits without clear salary indicators should use "deposit" not "salary"
        assertEquals(
                "deposit",
                mapping.getDetailed(),
                "ACH credit without salary indicators should be deposit, not salary");
    }

    @Test
    void testACHCreditOverridesIncorrectCategory() {
        // Given: ACH credit that might be incorrectly categorized as rent
        final String description = "ACH Credit - Salary";
        final String merchantName = "Employer";
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "RENT",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH credit should override rent category");
    }

    @Test
    void testACHCreditWithTransferInCategoryStillIncome() {
        // Given: ACH credit with TRANSFER_IN category
        final String description = "ACH Credit";
        final String merchantName = null;
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("1000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "TRANSFER_IN",
                        "TRANSFER_IN",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH credit should be income");
    }

    @Test
    void testACHDebitNotOverriddenToIncome() {
        // Given: ACH debit (negative amount) - should not be income
        final String description = "ACH Payment";
        final String merchantName = "Utility Company";
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("-100.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "UTILITIES",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then: Should remain as utilities (or payment if recurring), not income
        assertNotNull(mapping);
        assertNotEquals("income", mapping.getPrimary(), "ACH debit should not be income");
    }

    @Test
    void testNonACHTransactionNotAffected() {
        // Given: Non-ACH transaction
        final String description = "Online Purchase";
        final String merchantName = "Amazon";
        final String paymentChannel = "online";
        final BigDecimal amount = new BigDecimal("-50.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "GENERAL_MERCHANDISE",
                        "GENERAL_MERCHANDISE",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then: Should not be affected by ACH credit logic
        assertNotNull(mapping);
        assertNotEquals("income", mapping.getPrimary(), "Non-ACH transaction should not be income");
    }

    @Test
    void testACHElectronicCreditWithGustoPayCategorizedAsIncome() {
        // Given: ACH Electronic Credit with GUSTO PAY (no space between Credit and GUSTO)
        final String description = "ACH Electronic CreditGUSTO PAY 123456";
        final String merchantName = null;
        final String paymentChannel = null; // Payment channel might not be set
        final BigDecimal amount = new BigDecimal("5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "RENT_AND_UTILITIES_WATER",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals(
                "income",
                mapping.getPrimary(),
                "ACH Electronic Credit should be income, even with GUSTO PAY");
        // CRITICAL: "GUSTO PAY" contains payroll service name (Gusto), so should be categorized as
        // "salary"
        assertEquals(
                "salary",
                mapping.getDetailed(),
                "ACH Electronic Credit with GUSTO PAY should be salary (Gusto is a payroll service)");
    }

    @Test
    void testACHElectronicCreditByDescriptionCategorizedAsIncome() {
        // Given: ACH Electronic Credit in description (case-insensitive)
        final String description = "ACH ELECTRONIC CREDIT - Salary Payment";
        final String merchantName = null;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("3000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "UTILITIES",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals(
                "income",
                mapping.getPrimary(),
                "ACH Electronic Credit should be income by description");
        // Description contains "Salary Payment", so should be categorized as salary
        assertEquals(
                "salary",
                mapping.getDetailed(),
                "ACH credit with salary in description should be salary");
    }

    @Test
    void testACHCreditByDescriptionCategorizedAsIncome() {
        // Given: ACH Credit in description (without "Electronic")
        final String description = "ACH Credit - Direct Deposit";
        final String merchantName = null;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("2000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "TRANSFER_IN",
                        "TRANSFER_IN",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH Credit should be income by description");
        // Description contains "Direct Deposit", so should be categorized as salary
        assertEquals(
                "salary", mapping.getDetailed(), "ACH credit with direct deposit should be salary");
    }

    @Test
    void testACHDebitByDescriptionCategorizedAsPayment() {
        // Given: ACH Debit in description
        final String description = "ACH Debit - Bill Payment";
        final String merchantName = null;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-150.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "UTILITIES",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary(), "ACH Debit should be payment, not expense");
        assertEquals("payment", mapping.getDetailed());
    }

    @Test
    void testACHElectronicDebitByDescriptionCategorizedAsPayment() {
        // Given: ACH Electronic Debit in description
        final String description = "ACH Electronic Debit - Credit Card Payment";
        final String merchantName = null;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "LOAN_PAYMENTS",
                        "LOAN_PAYMENT",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary(), "ACH Electronic Debit should be payment");
        assertEquals("payment", mapping.getDetailed());
    }

    @Test
    void testACHDebitByChannelCategorizedAsPayment() {
        // Given: ACH debit by paymentChannel (negative amount)
        final String description = "Monthly Bill Payment";
        final String merchantName = "Utility Company";
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("-200.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "UTILITIES",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary(), "ACH debit (by channel) should be payment");
        assertEquals("payment", mapping.getDetailed());
    }

    @Test
    void testACHCreditNotCategorizedAsPayment() {
        // Given: ACH credit should be income, not payment
        final String description = "ACH Electronic CreditGUSTO PAY 123456";
        final String merchantName = null;
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "RENT",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH credit should be income, not payment");
        assertNotEquals("payment", mapping.getPrimary(), "ACH credit should not be payment");
    }

    @Test
    void testACHCreditWithSalaryKeywordsCategorizedAsSalary() {
        // Given: ACH credit with explicit salary keywords
        final String description = "ACH Credit - Salary Payment";
        final String merchantName = "Employer Corp";
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "RENT_AND_UTILITIES",
                        "RENT",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH credit should be income");
        // Description contains "Salary", so should be categorized as salary
        assertEquals(
                "salary",
                mapping.getDetailed(),
                "ACH credit with salary keywords should be salary");
    }

    @Test
    void testACHCreditWithPayrollKeywordsCategorizedAsSalary() {
        // Given: ACH credit with payroll keywords
        final String description = "ACH Credit - Payroll Deposit";
        final String merchantName = null;
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("3000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "TRANSFER_IN",
                        "TRANSFER_IN",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary());
        // Description contains "Payroll", so should be categorized as salary
        assertEquals(
                "salary",
                mapping.getDetailed(),
                "ACH credit with payroll keywords should be salary");
    }

    @Test
    void testACHCreditGenericCategorizedAsDeposit() {
        // Given: Generic ACH credit without salary/payroll keywords
        final String description = "ACH Electronic Credit";
        final String merchantName = null;
        final String paymentChannel = "ach";
        final BigDecimal amount = new BigDecimal("1000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "TRANSFER_IN",
                        "TRANSFER_IN",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary());
        assertEquals(
                "deposit",
                mapping.getDetailed(),
                "Generic ACH credit should be deposit, not salary");
    }
}
