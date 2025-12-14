package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for payment type detection in PlaidCategoryMapper
 */
@ExtendWith(MockitoExtension.class)
class PaymentTypeDetectionTest {

    @InjectMocks
    private PlaidCategoryMapper categoryMapper;

    @Test
    void testDetectCreditCardPayment_WithCreditCardPaymentDescription() {
        // Given
        String description = "Credit Card Payment - Chase";
        String merchantName = null;
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary());
        assertEquals("payment", mapping.getDetailed());
    }

    @Test
    void testDetectCreditCardPayment_WithCCPayment() {
        // Given
        String description = "CC Payment";
        String merchantName = null;
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-300.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary());
    }

    @Test
    void testDetectRecurringACHPayment_WithRecurringKeyword() {
        // Given
        String description = "Monthly Recurring Payment - Utilities";
        String merchantName = "Utility Company";
        String paymentChannel = "ach";
        BigDecimal amount = new BigDecimal("-100.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary());
        assertEquals("payment", mapping.getDetailed());
    }

    @Test
    void testDetectRecurringACHPayment_WithAutopay() {
        // Given
        String description = "AUTOPAY - Loan Payment";
        String merchantName = "Loan Company";
        String paymentChannel = "ach";
        BigDecimal amount = new BigDecimal("-250.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary());
    }

    @Test
    void testDetectRecurringACHPayment_WithBillPay() {
        // Given
        String description = "Bill Pay - Credit Card";
        String merchantName = null;
        String paymentChannel = "ach";
        BigDecimal amount = new BigDecimal("-200.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary());
    }

    @Test
    void testDetectRecurringACHPayment_WithNonACH_ReturnsOther() {
        // Given: Non-ACH transaction
        String description = "Online Purchase";
        String merchantName = "Amazon";
        String paymentChannel = "online";
        BigDecimal amount = new BigDecimal("-50.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then: Should not be payment (no payment channel or keywords)
        assertNotNull(mapping);
        assertNotEquals("payment", mapping.getPrimary());
    }

    @Test
    void testDetectRecurringACHPayment_WithPositiveAmount_NotPayment() {
        // Given: Positive ACH (credit, not payment)
        String description = "ACH Credit";
        String merchantName = null;
        String paymentChannel = "ach";
        BigDecimal amount = new BigDecimal("500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then: Should be income, not payment
        assertNotNull(mapping);
        assertNotEquals("payment", mapping.getPrimary());
        assertEquals("income", mapping.getPrimary()); // ACH credit should be income
    }

    @Test
    void testPaymentDetection_PriorityOverOtherCategories() {
        // Given: Credit card payment that might be categorized as other
        String description = "Credit Card Payment";
        String merchantName = null;
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "GENERAL_SERVICES", "GENERAL_SERVICES", merchantName, description, paymentChannel, amount
        );

        // Then: Payment should override other categories
        assertNotNull(mapping);
        assertEquals("payment", mapping.getPrimary());
    }
}

