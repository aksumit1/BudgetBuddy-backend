package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Comprehensive test suite for BalanceExtractor Tests global formats, edge cases, and error
 * conditions
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
    void testCreditCardBalanceUSFormat() {
        final List<String> headers =
                Arrays.asList("AMERICAN EXPRESS", "Account Ending: 1234", "New Balance: $1,234.56");
        final BigDecimal result = balanceExtractor.extractBalanceFromHeaders(headers, "creditCard");
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - US format with dollar sign and space (no colon)")
    void testCreditCardBalanceUSFormatNoColon() {
        final List<String> headers =
                Arrays.asList("AMERICAN EXPRESS", "Account Ending: 1234", "New Balance $1,746.59");
        final BigDecimal result = balanceExtractor.extractBalanceFromHeaders(headers, "creditCard");
        assertNotNull(result, "Should extract balance from 'New Balance $1,746.59'");
        assertEquals(
                new BigDecimal("1746.59"),
                result,
                "Should extract 1746.59 from 'New Balance $1,746.59'");
    }

    @Test
    @DisplayName("Extract credit card balance - European format")
    void testCreditCardBalanceEuropeanFormat() {
        final List<String> headers = Arrays.asList("VISA CARD", "Nouveau solde: 1.234,56 €");
        final BigDecimal result =
                balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - Indian format")
    void testCreditCardBalanceIndianFormat() {
        final List<String> headers =
                Arrays.asList("HDFC CREDIT CARD", "New Balance: ₹12,34,567.89");
        final BigDecimal result =
                balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234567.89"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - No currency symbol")
    void testCreditCardBalanceNoCurrency() {
        final List<String> headers = Arrays.asList("New Balance: 1234.56");
        final BigDecimal result =
                balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - Negative amount in parentheses")
    void testCreditCardBalanceNegativeParentheses() {
        final List<String> headers = Arrays.asList("New Balance: ($1,234.56)");
        final BigDecimal result =
                balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("-1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - Spanish label")
    void testCreditCardBalanceSpanishLabel() {
        final List<String> headers = Arrays.asList("Nuevo saldo: €1.234,56");
        final BigDecimal result =
                balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract credit card balance - French label")
    void testCreditCardBalanceFrenchLabel() {
        final List<String> headers = Arrays.asList("Nouveau solde: 1 234,56 €");
        final BigDecimal result =
                balanceExtractor.extractCreditCardBalance(String.join(" ", headers));
        assertNotNull(result);
        assertEquals(new BigDecimal("1234.56"), result);
    }

    @Test
    @DisplayName("Extract Discover card balance - Should NOT concatenate repeated values")
    void testDiscoverBalanceShouldNotConcatenateRepeatedValues() {
        // This is the problematic case: "New Balance: $5.66" followed by repeated values
        // Should extract ONLY $5.66, not concatenate to 56656656611
        final String text = "New Balance: $5.66, 5.66 5.66 11/13/2025, 5.66, 5.66, 11/13/2025";
        final BigDecimal result = balanceExtractor.extractCreditCardBalance(text);
        assertNotNull(result, "Should extract balance from Discover format");
        assertEquals(
                new BigDecimal("5.66"),
                result,
                "Should extract ONLY 5.66, not concatenate repeated values");
    }

    @Test
    @DisplayName("Extract Discover card balance - With repeated values after colon")
    void testDiscoverBalanceWithRepeatedValuesAfterColon() {
        // Another problematic case: "New Balance: $5.66 5.66 5.66 11/13/2025"
        final String text = "New Balance: $5.66 5.66 5.66 11/13/2025";
        final BigDecimal result = balanceExtractor.extractCreditCardBalance(text);
        assertNotNull(result, "Should extract balance from Discover format");
        assertEquals(
                new BigDecimal("5.66"),
                result,
                "Should extract ONLY 5.66, not concatenate repeated values");
    }
}
