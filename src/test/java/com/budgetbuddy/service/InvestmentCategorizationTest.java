package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Comprehensive tests for investment categorization with specific subcategories - CD deposits
 * should be "cd" (not generic investment) - Bonds, stocks, 401K, IRA, etc. should be correctly
 * categorized - Investment-related transactions should be correctly categorized
 */
// Tests intentionally pass null to verify graceful handling /
// AppException paths; SpotBugs's NP_LOAD_OF_KNOWN_NULL_VALUE is expected.
@SuppressFBWarnings(
        value = "NP_LOAD_OF_KNOWN_NULL_VALUE",
        justification = "Tests deliberately exercise null-input paths")
@ExtendWith(MockitoExtension.class)
class InvestmentCategorizationTest {

    private static final String INVESTMENT = "investment";
    private static final String BROKERAGE = "Brokerage";
    private static final String BANK = "Bank";

    @InjectMocks private PlaidCategoryMapper categoryMapper;

    @Test
    void testCDDepositCategorizedAsCD() {
        // Given
        final String description = "CD Deposit";
        final String merchantName = BANK;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("10000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "ENTERTAINMENT",
                        "ENTERTAINMENT",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals(
                INVESTMENT,
                mapping.getPrimary(),
                "CD deposit should be investment, not entertainment");
        assertEquals(
                "cd",
                mapping.getDetailed(),
                "CD deposit should be categorized as 'cd' subcategory");
    }

    @Test
    void testCDDepositCertificateOfDepositCategorizedAsCD() {
        // Given
        final String description = "Certificate of Deposit";
        final String merchantName = BANK;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "cd",
                mapping.getDetailed(),
                "Certificate of Deposit should be categorized as 'cd'");
    }

    @Test
    void testCDMaturityCategorizedAsCD() {
        // Given
        final String description = "CD Maturity";
        final String merchantName = BANK;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("10000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("cd", mapping.getDetailed(), "CD maturity should be categorized as 'cd'");
    }

    @Test
    void testCDInterestCategorizedAsCD() {
        // Given
        final String description = "CD Interest Payment";
        final String merchantName = BANK;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("50.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "INCOME",
                        "INTEREST_EARNED",
                        merchantName,
                        description,
                        paymentChannel,
                        amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary(), "CD interest should be investment");
        assertEquals("cd", mapping.getDetailed(), "CD interest should be categorized as 'cd'");
    }

    @Test
    void testCDDepositWithIncomeCategoryOverriddenToCD() {
        // Given: CD deposit that might be categorized as income
        final String description = "CD Deposit - Certificate of Deposit";
        final String merchantName = BANK;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        "INCOME", "SALARY", merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(
                INVESTMENT, mapping.getPrimary(), "CD deposit should be investment, not income");
        assertEquals("cd", mapping.getDetailed(), "CD deposit should be categorized as 'cd'");
    }

    // MARK: - Stocks Tests

    @Test
    void testStockPurchaseCategorizedAsStocks() {
        // Given
        final String description = "Stock Purchase - AAPL";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary(), "Stock purchase should be investment");
        assertEquals(
                "stocks",
                mapping.getDetailed(),
                "Stock purchase should be categorized as 'stocks'");
    }

    @Test
    void testStockEquityCategorizedAsStocks() {
        // Given
        final String description = "Equity Investment";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-2000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("stocks", mapping.getDetailed(), "Equity should be categorized as 'stocks'");
    }

    @Test
    void testCommonStockCategorizedAsStocks() {
        // Given
        final String description = "Common Stock Purchase";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "stocks", mapping.getDetailed(), "Common stock should be categorized as 'stocks'");
    }

    // MARK: - Bonds Tests

    @Test
    void testBondPurchaseCategorizedAsBonds() {
        // Given
        final String description = "Bond Purchase";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary(), "Bond purchase should be investment");
        assertEquals(
                "bonds", mapping.getDetailed(), "Bond purchase should be categorized as 'bonds'");
    }

    @Test
    void testMunicipalBondCategorizedAsMunicipalBonds() {
        // Given
        final String description = "Municipal Bond Purchase";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-3000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "municipalBonds",
                mapping.getDetailed(),
                "Municipal bond should be categorized as 'municipalBonds'");
    }

    @Test
    void testMuniBondCategorizedAsMunicipalBonds() {
        // Given
        final String description = "Muni Bond Investment";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-2500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "municipalBonds",
                mapping.getDetailed(),
                "Muni bond should be categorized as 'municipalBonds'");
    }

    @Test
    void testTreasuryBillCategorizedAsTBills() {
        // Given
        final String description = "Treasury Bill Purchase";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-10000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "tBills", mapping.getDetailed(), "Treasury Bill should be categorized as 'tBills'");
    }

    @Test
    void testTBillCategorizedAsTBills() {
        // Given
        final String description = "T-Bill Investment";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("tBills", mapping.getDetailed(), "T-Bill should be categorized as 'tBills'");
    }

    @Test
    void testUSTreasuryCategorizedAsTBills() {
        // Given
        final String description = "US Treasury Investment";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-7500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "tBills", mapping.getDetailed(), "US Treasury should be categorized as 'tBills'");
    }

    // MARK: - Retirement Account Tests

    @Test
    void test401kContributionCategorizedAsFourZeroOneK() {
        // Given
        final String description = "401k Contribution";
        final String merchantName = "Retirement Plan";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary(), "401k contribution should be investment");
        assertEquals(
                "fourZeroOneK",
                mapping.getDetailed(),
                "401k should be categorized as 'fourZeroOneK'");
    }

    @Test
    void test401kWithParenthesesCategorizedAsFourZeroOneK() {
        // Given
        final String description = "401(k) Contribution";
        final String merchantName = "Retirement Plan";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-600.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "fourZeroOneK",
                mapping.getDetailed(),
                "401(k) should be categorized as 'fourZeroOneK'");
    }

    @Test
    void test529PlanCategorizedAsFiveTwoNine() {
        // Given
        final String description = "529 Plan Contribution";
        final String merchantName = "College Savings";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-200.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "fiveTwoNine",
                mapping.getDetailed(),
                "529 Plan should be categorized as 'fiveTwoNine'");
    }

    @Test
    void test529CollegeSavingsCategorizedAsFiveTwoNine() {
        // Given
        final String description = "College Savings 529";
        final String merchantName = "Investment Company";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-300.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "fiveTwoNine",
                mapping.getDetailed(),
                "529 College Savings should be categorized as 'fiveTwoNine'");
    }

    @Test
    void testIRAContributionCategorizedAsIRA() {
        // Given
        final String description = "IRA Contribution";
        final String merchantName = "Retirement Plan";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("ira", mapping.getDetailed(), "IRA should be categorized as 'ira'");
    }

    @Test
    void testRothIRACategorizedAsIRA() {
        // Given
        final String description = "Roth IRA Contribution";
        final String merchantName = "Retirement Plan";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1200.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("ira", mapping.getDetailed(), "Roth IRA should be categorized as 'ira'");
    }

    @Test
    void testTraditionalIRACategorizedAsIRA() {
        // Given
        final String description = "Traditional IRA Contribution";
        final String merchantName = "Retirement Plan";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "ira", mapping.getDetailed(), "Traditional IRA should be categorized as 'ira'");
    }

    @Test
    void testSEPIRACategorizedAsIRA() {
        // Given
        final String description = "SEP IRA Contribution";
        final String merchantName = "Retirement Plan";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-2000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("ira", mapping.getDetailed(), "SEP IRA should be categorized as 'ira'");
    }

    // MARK: - Mutual Funds and ETF Tests

    @Test
    void testMutualFundCategorizedAsMutualFunds() {
        // Given
        final String description = "Mutual Fund Investment";
        final String merchantName = "Investment Company";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-2000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary(), "Mutual fund should be investment");
        assertEquals(
                "mutualFunds",
                mapping.getDetailed(),
                "Mutual fund should be categorized as 'mutualFunds'");
    }

    @Test
    void testETFCategorizedAsETF() {
        // Given
        final String description = "ETF Purchase";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("etf", mapping.getDetailed(), "ETF should be categorized as 'etf'");
    }

    @Test
    void testExchangeTradedFundCategorizedAsETF() {
        // Given
        final String description = "Exchange Traded Fund Investment";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1800.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "etf",
                mapping.getDetailed(),
                "Exchange Traded Fund should be categorized as 'etf'");
    }

    // MARK: - Money Market Tests

    @Test
    void testMoneyMarketCategorizedAsMoneyMarket() {
        // Given
        final String description = "Money Market Account";
        final String merchantName = BANK;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "moneyMarket",
                mapping.getDetailed(),
                "Money Market should be categorized as 'moneyMarket'");
    }

    @Test
    void testMMAccountCategorizedAsMoneyMarket() {
        // Given
        final String description = "MM Account Investment";
        final String merchantName = BANK;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-3000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "moneyMarket",
                mapping.getDetailed(),
                "MM Account should be categorized as 'moneyMarket'");
    }

    // MARK: - Precious Metals Tests

    @Test
    void testGoldInvestmentCategorizedAsPreciousMetals() {
        // Given
        final String description = "Gold Investment";
        final String merchantName = "Precious Metals Dealer";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-10000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "preciousMetals",
                mapping.getDetailed(),
                "Gold should be categorized as 'preciousMetals'");
    }

    @Test
    void testSilverInvestmentCategorizedAsPreciousMetals() {
        // Given
        final String description = "Silver Bullion Purchase";
        final String merchantName = "Precious Metals Dealer";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "preciousMetals",
                mapping.getDetailed(),
                "Silver should be categorized as 'preciousMetals'");
    }

    @Test
    void testPlatinumInvestmentCategorizedAsPreciousMetals() {
        // Given
        final String description = "Platinum Investment";
        final String merchantName = "Precious Metals Dealer";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-8000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "preciousMetals",
                mapping.getDetailed(),
                "Platinum should be categorized as 'preciousMetals'");
    }

    // MARK: - Crypto Tests

    @Test
    void testBitcoinInvestmentCategorizedAsCrypto() {
        // Given
        final String description = "Bitcoin Purchase";
        final String merchantName = "Crypto Exchange";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-2000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("crypto", mapping.getDetailed(), "Bitcoin should be categorized as 'crypto'");
    }

    @Test
    void testEthereumInvestmentCategorizedAsCrypto() {
        // Given
        final String description = "Ethereum Investment";
        final String merchantName = "Crypto Exchange";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1500.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("crypto", mapping.getDetailed(), "Ethereum should be categorized as 'crypto'");
    }

    @Test
    void testCryptocurrencyCategorizedAsCrypto() {
        // Given
        final String description = "Cryptocurrency Investment";
        final String merchantName = "Crypto Exchange";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-3000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "crypto",
                mapping.getDetailed(),
                "Cryptocurrency should be categorized as 'crypto'");
    }

    @Test
    void testBTCCategorizedAsCrypto() {
        // Given
        final String description = "BTC Purchase";
        final String merchantName = "Crypto Exchange";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-1000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals("crypto", mapping.getDetailed(), "BTC should be categorized as 'crypto'");
    }

    // MARK: - Other Investment Tests

    @Test
    void testBrokerageAccountCategorizedAsOtherInvestment() {
        // Given
        final String description = "Brokerage Account Investment";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-5000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "otherInvestment",
                mapping.getDetailed(),
                "Brokerage should be categorized as 'otherInvestment'");
    }

    @Test
    void testRetirementAccountCategorizedAsOtherInvestment() {
        // Given
        final String description = "Retirement Account Investment";
        final String merchantName = "Retirement Plan";
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-2000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "otherInvestment",
                mapping.getDetailed(),
                "Generic retirement should be categorized as 'otherInvestment'");
    }

    @Test
    void testSecuritiesTradingCategorizedAsOtherInvestment() {
        // Given
        final String description = "Securities Trading";
        final String merchantName = BROKERAGE;
        final String paymentChannel = null;
        final BigDecimal amount = new BigDecimal("-3000.00");

        // When
        final PlaidCategoryMapper.CategoryMapping mapping =
                categoryMapper.mapPlaidCategory(
                        null, null, merchantName, description, paymentChannel, amount);

        // Then
        assertNotNull(mapping);
        assertEquals(INVESTMENT, mapping.getPrimary());
        assertEquals(
                "otherInvestment",
                mapping.getDetailed(),
                "Securities trading should be categorized as 'otherInvestment'");
    }
}
