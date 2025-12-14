package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ACH credit categorization fixes
 * - ACH credits (positive amounts) should be income, not rent/expense
 */
@ExtendWith(MockitoExtension.class)
class ACHCreditCategorizationTest {

    @InjectMocks
    private PlaidCategoryMapper categoryMapper;

    @Test
    void testACHCredit_CategorizedAsIncome() {
        // Given: ACH credit transaction
        String description = "ACH Credit";
        String merchantName = null;
        String paymentChannel = "ach";
        BigDecimal amount = new BigDecimal("500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "RENT_AND_UTILITIES", "RENT", merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH credit should be income, not rent");
        assertEquals("income", mapping.getDetailed());
    }

    @Test
    void testACHCredit_OverridesIncorrectCategory() {
        // Given: ACH credit that might be incorrectly categorized as rent
        String description = "ACH Credit - Salary";
        String merchantName = "Employer";
        String paymentChannel = "ach";
        BigDecimal amount = new BigDecimal("5000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "RENT_AND_UTILITIES", "RENT", merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH credit should override rent category");
    }

    @Test
    void testACHCredit_WithTransferInCategory_StillIncome() {
        // Given: ACH credit with TRANSFER_IN category
        String description = "ACH Credit";
        String merchantName = null;
        String paymentChannel = "ach";
        BigDecimal amount = new BigDecimal("1000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "TRANSFER_IN", "TRANSFER_IN", merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("income", mapping.getPrimary(), "ACH credit should be income");
    }

    @Test
    void testACHDebit_NotOverriddenToIncome() {
        // Given: ACH debit (negative amount) - should not be income
        String description = "ACH Payment";
        String merchantName = "Utility Company";
        String paymentChannel = "ach";
        BigDecimal amount = new BigDecimal("-100.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "RENT_AND_UTILITIES", "UTILITIES", merchantName, description, paymentChannel, amount
        );

        // Then: Should remain as utilities (or payment if recurring), not income
        assertNotNull(mapping);
        assertNotEquals("income", mapping.getPrimary(), "ACH debit should not be income");
    }

    @Test
    void testNonACHTransaction_NotAffected() {
        // Given: Non-ACH transaction
        String description = "Online Purchase";
        String merchantName = "Amazon";
        String paymentChannel = "online";
        BigDecimal amount = new BigDecimal("-50.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "GENERAL_MERCHANDISE", "GENERAL_MERCHANDISE", merchantName, description, paymentChannel, amount
        );

        // Then: Should not be affected by ACH credit logic
        assertNotNull(mapping);
        assertNotEquals("income", mapping.getPrimary(), "Non-ACH transaction should not be income");
    }
}

