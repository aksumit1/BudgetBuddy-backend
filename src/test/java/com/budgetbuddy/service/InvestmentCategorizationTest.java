package com.budgetbuddy.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for investment categorization with specific subcategories
 * - CD deposits should be "cd" (not generic investment)
 * - Bonds, stocks, 401K, IRA, etc. should be correctly categorized
 * - Investment-related transactions should be correctly categorized
 */
@ExtendWith(MockitoExtension.class)
class InvestmentCategorizationTest {

    @InjectMocks
    private PlaidCategoryMapper categoryMapper;

    @Test
    void testCDDeposit_CategorizedAsCD() {
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
        assertEquals("cd", mapping.getDetailed(), "CD deposit should be categorized as 'cd' subcategory");
    }
    
    @Test
    void testCDDeposit_CertificateOfDeposit_CategorizedAsCD() {
        // Given
        String description = "Certificate of Deposit";
        String merchantName = "Bank";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("5000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("cd", mapping.getDetailed(), "Certificate of Deposit should be categorized as 'cd'");
    }
    
    @Test
    void testCDMaturity_CategorizedAsCD() {
        // Given
        String description = "CD Maturity";
        String merchantName = "Bank";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("10000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("cd", mapping.getDetailed(), "CD maturity should be categorized as 'cd'");
    }
    
    @Test
    void testCDInterest_CategorizedAsCD() {
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
        assertEquals("cd", mapping.getDetailed(), "CD interest should be categorized as 'cd'");
    }

    @Test
    void testCDDeposit_WithIncomeCategory_OverriddenToCD() {
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
        assertEquals("cd", mapping.getDetailed(), "CD deposit should be categorized as 'cd'");
    }

    // MARK: - Stocks Tests
    
    @Test
    void testStockPurchase_CategorizedAsStocks() {
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
        assertEquals("stocks", mapping.getDetailed(), "Stock purchase should be categorized as 'stocks'");
    }
    
    @Test
    void testStockEquity_CategorizedAsStocks() {
        // Given
        String description = "Equity Investment";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-2000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("stocks", mapping.getDetailed(), "Equity should be categorized as 'stocks'");
    }
    
    @Test
    void testCommonStock_CategorizedAsStocks() {
        // Given
        String description = "Common Stock Purchase";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("stocks", mapping.getDetailed(), "Common stock should be categorized as 'stocks'");
    }

    // MARK: - Bonds Tests
    
    @Test
    void testBondPurchase_CategorizedAsBonds() {
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
        assertEquals("bonds", mapping.getDetailed(), "Bond purchase should be categorized as 'bonds'");
    }
    
    @Test
    void testMunicipalBond_CategorizedAsMunicipalBonds() {
        // Given
        String description = "Municipal Bond Purchase";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-3000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("municipalBonds", mapping.getDetailed(), "Municipal bond should be categorized as 'municipalBonds'");
    }
    
    @Test
    void testMuniBond_CategorizedAsMunicipalBonds() {
        // Given
        String description = "Muni Bond Investment";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-2500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("municipalBonds", mapping.getDetailed(), "Muni bond should be categorized as 'municipalBonds'");
    }
    
    @Test
    void testTreasuryBill_CategorizedAsTBills() {
        // Given
        String description = "Treasury Bill Purchase";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-10000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("tBills", mapping.getDetailed(), "Treasury Bill should be categorized as 'tBills'");
    }
    
    @Test
    void testTBill_CategorizedAsTBills() {
        // Given
        String description = "T-Bill Investment";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-5000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("tBills", mapping.getDetailed(), "T-Bill should be categorized as 'tBills'");
    }
    
    @Test
    void testUSTreasury_CategorizedAsTBills() {
        // Given
        String description = "US Treasury Investment";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-7500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("tBills", mapping.getDetailed(), "US Treasury should be categorized as 'tBills'");
    }

    // MARK: - Retirement Account Tests
    
    @Test
    void test401kContribution_CategorizedAsFourZeroOneK() {
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
        assertEquals("fourZeroOneK", mapping.getDetailed(), "401k should be categorized as 'fourZeroOneK'");
    }
    
    @Test
    void test401kWithParentheses_CategorizedAsFourZeroOneK() {
        // Given
        String description = "401(k) Contribution";
        String merchantName = "Retirement Plan";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-600.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("fourZeroOneK", mapping.getDetailed(), "401(k) should be categorized as 'fourZeroOneK'");
    }
    
    @Test
    void test529Plan_CategorizedAsFiveTwoNine() {
        // Given
        String description = "529 Plan Contribution";
        String merchantName = "College Savings";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-200.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("fiveTwoNine", mapping.getDetailed(), "529 Plan should be categorized as 'fiveTwoNine'");
    }
    
    @Test
    void test529CollegeSavings_CategorizedAsFiveTwoNine() {
        // Given
        String description = "College Savings 529";
        String merchantName = "Investment Company";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-300.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("fiveTwoNine", mapping.getDetailed(), "529 College Savings should be categorized as 'fiveTwoNine'");
    }
    
    @Test
    void testIRAContribution_CategorizedAsIRA() {
        // Given
        String description = "IRA Contribution";
        String merchantName = "Retirement Plan";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("ira", mapping.getDetailed(), "IRA should be categorized as 'ira'");
    }
    
    @Test
    void testRothIRA_CategorizedAsIRA() {
        // Given
        String description = "Roth IRA Contribution";
        String merchantName = "Retirement Plan";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1200.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("ira", mapping.getDetailed(), "Roth IRA should be categorized as 'ira'");
    }
    
    @Test
    void testTraditionalIRA_CategorizedAsIRA() {
        // Given
        String description = "Traditional IRA Contribution";
        String merchantName = "Retirement Plan";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("ira", mapping.getDetailed(), "Traditional IRA should be categorized as 'ira'");
    }
    
    @Test
    void testSEPIRA_CategorizedAsIRA() {
        // Given
        String description = "SEP IRA Contribution";
        String merchantName = "Retirement Plan";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-2000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("ira", mapping.getDetailed(), "SEP IRA should be categorized as 'ira'");
    }

    // MARK: - Mutual Funds and ETF Tests
    
    @Test
    void testMutualFund_CategorizedAsMutualFunds() {
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
        assertEquals("mutualFunds", mapping.getDetailed(), "Mutual fund should be categorized as 'mutualFunds'");
    }
    
    @Test
    void testETF_CategorizedAsETF() {
        // Given
        String description = "ETF Purchase";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("etf", mapping.getDetailed(), "ETF should be categorized as 'etf'");
    }
    
    @Test
    void testExchangeTradedFund_CategorizedAsETF() {
        // Given
        String description = "Exchange Traded Fund Investment";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1800.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("etf", mapping.getDetailed(), "Exchange Traded Fund should be categorized as 'etf'");
    }

    // MARK: - Money Market Tests
    
    @Test
    void testMoneyMarket_CategorizedAsMoneyMarket() {
        // Given
        String description = "Money Market Account";
        String merchantName = "Bank";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-5000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("moneyMarket", mapping.getDetailed(), "Money Market should be categorized as 'moneyMarket'");
    }
    
    @Test
    void testMMAccount_CategorizedAsMoneyMarket() {
        // Given
        String description = "MM Account Investment";
        String merchantName = "Bank";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-3000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("moneyMarket", mapping.getDetailed(), "MM Account should be categorized as 'moneyMarket'");
    }

    // MARK: - Precious Metals Tests
    
    @Test
    void testGoldInvestment_CategorizedAsPreciousMetals() {
        // Given
        String description = "Gold Investment";
        String merchantName = "Precious Metals Dealer";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-10000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("preciousMetals", mapping.getDetailed(), "Gold should be categorized as 'preciousMetals'");
    }
    
    @Test
    void testSilverInvestment_CategorizedAsPreciousMetals() {
        // Given
        String description = "Silver Bullion Purchase";
        String merchantName = "Precious Metals Dealer";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-5000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("preciousMetals", mapping.getDetailed(), "Silver should be categorized as 'preciousMetals'");
    }
    
    @Test
    void testPlatinumInvestment_CategorizedAsPreciousMetals() {
        // Given
        String description = "Platinum Investment";
        String merchantName = "Precious Metals Dealer";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-8000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("preciousMetals", mapping.getDetailed(), "Platinum should be categorized as 'preciousMetals'");
    }

    // MARK: - Crypto Tests
    
    @Test
    void testBitcoinInvestment_CategorizedAsCrypto() {
        // Given
        String description = "Bitcoin Purchase";
        String merchantName = "Crypto Exchange";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-2000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("crypto", mapping.getDetailed(), "Bitcoin should be categorized as 'crypto'");
    }
    
    @Test
    void testEthereumInvestment_CategorizedAsCrypto() {
        // Given
        String description = "Ethereum Investment";
        String merchantName = "Crypto Exchange";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1500.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("crypto", mapping.getDetailed(), "Ethereum should be categorized as 'crypto'");
    }
    
    @Test
    void testCryptocurrency_CategorizedAsCrypto() {
        // Given
        String description = "Cryptocurrency Investment";
        String merchantName = "Crypto Exchange";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-3000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("crypto", mapping.getDetailed(), "Cryptocurrency should be categorized as 'crypto'");
    }
    
    @Test
    void testBTC_CategorizedAsCrypto() {
        // Given
        String description = "BTC Purchase";
        String merchantName = "Crypto Exchange";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-1000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("crypto", mapping.getDetailed(), "BTC should be categorized as 'crypto'");
    }

    // MARK: - Other Investment Tests
    
    @Test
    void testBrokerageAccount_CategorizedAsOtherInvestment() {
        // Given
        String description = "Brokerage Account Investment";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-5000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("otherInvestment", mapping.getDetailed(), "Brokerage should be categorized as 'otherInvestment'");
    }
    
    @Test
    void testRetirementAccount_CategorizedAsOtherInvestment() {
        // Given
        String description = "Retirement Account Investment";
        String merchantName = "Retirement Plan";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-2000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("otherInvestment", mapping.getDetailed(), "Generic retirement should be categorized as 'otherInvestment'");
    }
    
    @Test
    void testSecuritiesTrading_CategorizedAsOtherInvestment() {
        // Given
        String description = "Securities Trading";
        String merchantName = "Brokerage";
        String paymentChannel = null;
        BigDecimal amount = new BigDecimal("-3000.00");

        // When
        PlaidCategoryMapper.CategoryMapping mapping = categoryMapper.mapPlaidCategory(
            null, null, merchantName, description, paymentChannel, amount
        );

        // Then
        assertNotNull(mapping);
        assertEquals("investment", mapping.getPrimary());
        assertEquals("otherInvestment", mapping.getDetailed(), "Securities trading should be categorized as 'otherInvestment'");
    }
}

