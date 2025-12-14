package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for investment categorization fixes
 * - CD deposits should be investment (not entertainment or income)
 * - Investment-related transactions should be correctly categorized
 */
@ExtendWith(MockitoExtension.class)
class InvestmentCategorizationTest {

    @InjectMocks
    private PlaidCategoryMapper categoryMapper;

    @Test
    void testCDDeposit_CategorizedAsInvestment() {
        // Given
        String description = "CD Deposit";
        String merchantName = "Bank";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("10000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "ENTERTAINMENT", "ENTERTAINMENT", merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary(), "CD deposit should be investment, not entertainment");
        assertEquals("investment", mapping.getDetailed());
    }

    @Test
    void testCDDeposit_WithIncomeCategory_OverriddenToInvestment() {
        // Given: CD deposit that might be categorized as income
        String description = "CD Deposit - Certificate of Deposit";
        String merchantName = "Bank";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("5000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "INCOME", "SALARY", merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary(), "CD deposit should be investment, not income");
    }

    @Test
    void testCDInterest_CategorizedAsInvestment() {
        // Given
        String description = "CD Interest Payment";
        String merchantName = "Bank";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("50.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            "INCOME", "INTEREST_EARNED", merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary(), "CD interest should be investment");
    }

    @Test
    void testStockPurchase_CategorizedAsInvestment() {
        // Given
        String description = "Stock Purchase - AAPL";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary(), "Stock purchase should be investment");
    }

    @Test
    void testBondPurchase_CategorizedAsInvestment() {
        // Given
        String description = "Bond Purchase";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-5000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary(), "Bond purchase should be investment");
    }

    @Test
    void testMutualFund_CategorizedAsInvestment() {
        // Given
        String description = "Mutual Fund Investment";
        String merchantName = "Investment Company";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-2000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary(), "Mutual fund should be investment");
    }

    @Test
    void test401kContribution_CategorizedAsInvestment() {
        // Given
        String description = "401k Contribution";
        String merchantName = "Retirement Plan";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary(), "401k contribution should be investment");
    }
}

