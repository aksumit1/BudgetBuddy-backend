package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for BalanceExtractor
 * Tests global formats, edge cases, and error conditions
 */
class BalanceExtractorTest {

    private BalanceExtractor balanceExtractor;

    @BeforeEach
    void setUp() {
        balanceExtractor = new BalanceExtractor();
    }

    // ========== Credit Card Balance Tests ==========

    @Test
    @DisplayName("Extract credit card balance - US format with dollar sign")
    void testCreditCardBalance_USFormat() {
        List<String> headers = Arrays.asList(
            "AMERICAN EXPRESS",
            "Account Ending: 1234",
            "New Balance: $1,234.56"
        );
        BigDecimal result = balanceExtractor.extractBalanceFromHeaders(headers, "creditCard");
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - US format with dollar sign and space (no colon)")
    void testCreditCardBalance_USFormat_NoColon() {
        List<String> headers = Arrays.asList(
            "AMERICAN EXPRESS",
            "Account Ending: 1234",
            "New Balance $1,746.59"
        );
        BigDecimal result = balanceExtractor.extractBalanceFromHeaders(headers, "creditCard");
        assertNotNull(result, "Should extract balance from 'New Balance $1,746.59'");
        assertEquals(new BigDecimal("1746.59"), result, "Should extract 1746.59 from 'New Balance $1,746.59'");
    }

    @Test
    @DisplayName("Extract credit card balance - European format")
    void testCreditCardBalance_EuropeanFormat() {
        List<String> headers = Arrays.asList(
            "VISA CARD",
            "Nouveau solde: 1.234,56 €"
        );
        BigDecimal result = balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - Indian format")
    void testCreditCardBalance_IndianFormat() {
        List<String> headers = Arrays.asList(
            "HDFC CREDIT CARD",
            "New Balance: ₹12,34,567.89"
        );
        BigDecimal result = balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234567.89"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - No currency symbol")
    void testCreditCardBalance_NoCurrency() {
        List<String> headers = Arrays.asList("New Balance: 1234.56");
        BigDecimal result = balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - Negative amount in parentheses")
    void testCreditCardBalance_NegativeParentheses() {
        List<String> headers = Arrays.asList("New Balance: ($1,234.56)");
        BigDecimal result = balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("-1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - Spanish label")
    void testCreditCardBalance_SpanishLabel() {
        List<String> headers = Arrays.asList("Nuevo saldo: €1.234,56");
        BigDecimal result = balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - French label")
    void testCreditCardBalance_FrenchLabel() {
        List<String> headers = Arrays.asList("Nouveau solde: 1 234,56 €");
        BigDecimal result = balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract Discover card balance - Should NOT concatenate repeated values")
    void testDiscoverBalance_ShouldNotConcatenateRepeatedValues() {
        // This is the problematic case: "New Balance: $5.66" followed by repeated values
        // Should extract ONLY $5.66, not concatenate to 56656656611
        String text = "New Balance: $5.66, 5.66 5.66 11/13/2025, 5.66, 5.66, 11/13/2025";
        BigDecimal result = balanceExtractor.extractCreditCardBalance(text);
        assertNotNull(result, "Should extract balance from Discover format");
        assertEquals(new BigDecimal("5.66"), result, "Should extract ONLY 5.66, not concatenate repeated values");
    }

    @Test
    @DisplayName("Extract Discover card balance - With repeated values after colon")
    void testDiscoverBalance_WithRepeatedValuesAfterColon() {
        // Another problematic case: "New Balance: $5.66 5.66 5.66 11/13/2025"
        String text = "New Balance: $5.66 5.66 5.66 11/13/2025";
        BigDecimal result = balanceExtractor.extractCreditCardBalance(text);
        assertNotNull(result, "Should extract balance from Discover format");
        assertEquals(new BigDecimal("5.66"), result, "Should extract ONLY 5.66, not concatenate repeated values");
    }
}