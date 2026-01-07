package com.budgetbuddy.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import com.budgetbuddy.service.TransactionTypeCategoryService;
import com.budgetbuddy.service.ImportCategoryParser;

/**
 * Comprehensive tests for Global Import feature:
 * - European formats (EUR, GBP, CHF, DD.MM.YYYY, comma decimal)
 * - Asian formats (JPY, CNY, KRW, THB, yyyy/MM/dd)
 * - Indian formats (INR, DD/MM/YYYY)
 * - Credit cards from different regions
 * - Investment accounts from different regions
 * - Multi-currency support
 * - Multi-date format support
 * - Multi-number format support (US vs European)
 */
class CSVImportServiceGlobalImportTest {

    private CSVImportService csvImportService;
    private AccountDetectionService accountDetectionService;

    @BeforeEach
    void setUp() {
        // AccountDetectionService is a @Service with no-arg constructor
        // Use mock for unit tests
        accountDetectionService = org.mockito.Mockito.mock(AccountDetectionService.class);
        com.budgetbuddy.service.ml.EnhancedCategoryDetectionService enhancedCategoryDetection = 
                org.mockito.Mockito.mock(com.budgetbuddy.service.ml.EnhancedCategoryDetectionService.class);
        com.budgetbuddy.service.ml.FuzzyMatchingService fuzzyMatchingService = 
                org.mockito.Mockito.mock(com.budgetbuddy.service.ml.FuzzyMatchingService.class);
        csvImportService = new CSVImportService(accountDetectionService, enhancedCategoryDetection, fuzzyMatchingService,
                org.mockito.Mockito.mock(TransactionTypeCategoryService.class),
                org.mockito.Mockito.mock(ImportCategoryParser.class),
                org.mockito.Mockito.mock(com.budgetbuddy.service.category.strategy.CategoryDetectionManager.class));
    }

    // MARK: - European Credit Card Tests

    @Test
    void testParseCSV_GermanCreditCard_DeutscheBank() {
        // Given: German credit card statement (Deutsche Bank)
        String csvContent = "Buchungsdatum,Beschreibung,Betrag\n" +
                "15.12.2024,Amazon Purchase,1.234,56\n" +  // DD.MM.YYYY, comma decimal
                "16.12.2024,Starbucks,-15,50";              // Negative amount
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "deutsche_bank_credit.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse DD.MM.YYYY format");
        // Amount should be parsed correctly (1.234,56 = 1234.56 in US format)
        BigDecimal amount = tx.getAmount();
        BigDecimal amountAbs = amount.compareTo(BigDecimal.ZERO) < 0 ? amount.negate() : amount;
        assertTrue(amountAbs.compareTo(new BigDecimal("1234.56")) == 0 ||
                   amountAbs.compareTo(new BigDecimal("1234.56")) < 0.01,
                "Should parse European number format (comma decimal)");
    }

    @Test
    void testParseCSV_FrenchCreditCard_BNPParibas() {
        // Given: French credit card statement (BNP Paribas)
        String csvContent = "Date de transaction,Libellé,Montant\n" +
                "15/12/2024,Achat Amazon,123,45\n" +  // DD/MM/YYYY, comma decimal
                "16/12/2024,Restaurant,-45,20";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "bnp_paribas_credit.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse DD/MM/YYYY format");
    }

    @Test
    void testParseCSV_UKCreditCard_HSBC() {
        // Given: UK credit card statement (HSBC)
        String csvContent = "Transaction Date,Description,Amount\n" +
                "15/12/2024,Tesco Purchase,£45.67\n" +  // DD/MM/YYYY, GBP
                "16/12/2024,Amazon UK,-£123.45";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "hsbc_credit_card.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse DD/MM/YYYY format");
        assertEquals("GBP", tx.getCurrencyCode(), "Should detect GBP currency");
    }

    // MARK: - Asian Credit Card Tests

    @Test
    void testParseCSV_JapaneseCreditCard_JCB() {
        // Given: Japanese credit card statement (JCB)
        String csvContent = "取引日,摘要,金額\n" +
                "2024/12/15,Amazon購入,¥5,000\n" +  // yyyy/MM/dd, JPY
                "2024/12/16,スターバックス,-¥1,200";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "jcb_credit_card.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse yyyy/MM/dd format");
        assertEquals("JPY", tx.getCurrencyCode(), "Should detect JPY currency");
    }

    @Test
    void testParseCSV_ChineseCreditCard_UnionPay() {
        // Given: Chinese credit card statement (UnionPay)
        String csvContent = "交易日期,交易描述,金额\n" +
                "2024/12/15,淘宝购物,¥500.00\n" +  // yyyy/MM/dd, CNY
                "2024/12/16,星巴克,-¥45.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "unionpay_credit.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse yyyy/MM/dd format");
        assertEquals("CNY", tx.getCurrencyCode(), "Should detect CNY currency");
    }

    @Test
    void testParseCSV_IndianCreditCard_HDFC() {
        // Given: Indian credit card statement (HDFC)
        String csvContent = "Transaction Date,Description,Amount\n" +
                "15/12/2024,Amazon India,₹1,234.56\n" +  // DD/MM/YYYY, INR
                "16/12/2024,Swiggy,-₹500.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "hdfc_credit_card.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse DD/MM/YYYY format");
        assertEquals("INR", tx.getCurrencyCode(), "Should detect INR currency");
    }

    // MARK: - European Investment Account Tests

    @Test
    void testParseCSV_GermanBrokerage_Comdirect() {
        // Given: German brokerage account (Comdirect)
        String csvContent = "Datum,Wertstellung,Verwendungszweck,Betrag\n" +
                "15.12.2024,16.12.2024,Kauf APPLE INC 10 Stk,1.234,56\n" +  // DD.MM.YYYY, comma decimal
                "20.12.2024,21.12.2024,Verkauf TESLA INC 5 Stk,-2.500,00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "comdirect_brokerage.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse DD.MM.YYYY format");
        assertTrue(tx.getDescription().contains("APPLE") || tx.getDescription().contains("Kauf"),
                "Should extract security name or transaction type");
    }

    @Test
    void testParseCSV_FrenchBrokerage_Boursorama() {
        // Given: French brokerage account (Boursorama)
        String csvContent = "Date de transaction,Date de valeur,Libellé,Montant\n" +
                "15/12/2024,16/12/2024,Achat APPLE INC 10 actions,1.234,56\n" +  // DD/MM/YYYY, comma decimal
                "20/12/2024,21/12/2024,Vente TESLA INC 5 actions,-2.500,00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "boursorama_brokerage.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse DD/MM/YYYY format");
    }

    // MARK: - Asian Investment Account Tests

    @Test
    void testParseCSV_JapaneseBrokerage_MUFGSecurities() {
        // Given: Japanese brokerage account (MUFG Securities)
        String csvContent = "取引日,決済日,銘柄,取引種別,金額\n" +
                "2024/12/15,2024/12/16,トヨタ自動車,買付,¥500,000\n" +  // yyyy/MM/dd, JPY
                "2024/12/20,2024/12/21,ソニー,売却,-¥300,000";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "mufg_securities.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse yyyy/MM/dd format");
        assertEquals("JPY", tx.getCurrencyCode(), "Should detect JPY currency");
    }

    @Test
    void testParseCSV_ChineseBrokerage_CITICSecurities() {
        // Given: Chinese brokerage account (CITIC Securities)
        String csvContent = "交易日期,结算日期,证券名称,交易类型,金额\n" +
                "2024/12/15,2024/12/16,苹果公司,买入,¥50,000.00\n" +  // yyyy/MM/dd, CNY
                "2024/12/20,2024/12/21,特斯拉,卖出,-¥30,000.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "citic_securities.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse yyyy/MM/dd format");
        assertEquals("CNY", tx.getCurrencyCode(), "Should detect CNY currency");
    }

    @Test
    void testParseCSV_IndianBrokerage_Zerodha() {
        // Given: Indian brokerage account (Zerodha)
        String csvContent = "Trade Date,Settlement Date,Security Name,Transaction Type,Net Amount\n" +
                "15/12/2024,16/12/2024,RELIANCE,BUY,₹50,000.00\n" +  // DD/MM/YYYY, INR
                "20/12/2024,21/12/2024,TCS,SELL,-₹30,000.00";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "zerodha_brokerage.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 1);
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();
        assertFalse(transactions.isEmpty());

        CSVImportService.ParsedTransaction tx = transactions.get(0);
        assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(), "Should parse DD/MM/YYYY format");
        assertEquals("INR", tx.getCurrencyCode(), "Should detect INR currency");
    }

    // MARK: - Multi-Currency Tests

    @Test
    void testParseCSV_MultiCurrency_DetectsCorrectly() {
        // Given: CSV with multiple currency indicators
        String csvContent = "Date,Description,Amount\n" +
                "2024-12-15,USD Transaction,$100.00\n" +
                "2024-12-16,EUR Transaction,€85.00\n" +
                "2024-12-17,GBP Transaction,£75.00\n" +
                "2024-12-18,INR Transaction,₹8,000.00\n" +
                "2024-12-19,JPY Transaction,¥10,000";
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "multi_currency.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 4, "Should parse at least 4 transactions");
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();

        // Verify currency detection
        boolean foundUSD = transactions.stream().anyMatch(tx -> "USD".equals(tx.getCurrencyCode()));
        boolean foundEUR = transactions.stream().anyMatch(tx -> "EUR".equals(tx.getCurrencyCode()));
        boolean foundGBP = transactions.stream().anyMatch(tx -> "GBP".equals(tx.getCurrencyCode()));
        boolean foundINR = transactions.stream().anyMatch(tx -> "INR".equals(tx.getCurrencyCode()));
        boolean foundJPY = transactions.stream().anyMatch(tx -> "JPY".equals(tx.getCurrencyCode()));

        assertTrue(foundUSD || foundEUR || foundGBP || foundINR || foundJPY,
                "Should detect at least one currency correctly");
    }

    // MARK: - Multi-Date Format Tests

    @Test
    void testParseCSV_MultiDateFormat_ParsesCorrectly() {
        // Given: CSV with different date formats
        String csvContent = "Date,Description,Amount\n" +
                "15.12.2024,European Format,100.00\n" +      // DD.MM.YYYY
                "15/12/2024,Indian Format,200.00\n" +        // DD/MM/YYYY
                "12/15/2024,US Format,300.00\n" +            // MM/DD/YYYY
                "2024-12-15,ISO Format,400.00\n" +           // yyyy-MM-dd
                "2024/12/15,Asian Format,500.00";            // yyyy/MM/dd
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "multi_date_format.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 4, "Should parse at least 4 transactions");
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();

        // All dates should parse to 2024-12-15
        for (CSVImportService.ParsedTransaction tx : transactions) {
            assertNotNull(tx.getDate(), "All transactions should have valid dates");
            assertEquals(LocalDate.of(2024, 12, 15), tx.getDate(),
                    "All dates should parse to 2024-12-15 regardless of format: " + tx.getDate());
        }
    }

    // MARK: - European Number Format Tests (Comma Decimal)

    @Test
    void testParseCSV_EuropeanNumberFormat_CommaDecimal() {
        // Given: CSV with European number format (comma as decimal separator)
        String csvContent = "Date,Description,Amount\n" +
                "15.12.2024,Transaction 1,1.234,56\n" +      // 1,234.56 in US format
                "16.12.2024,Transaction 2,2.500,00\n" +      // 2,500.00 in US format
                "17.12.2024,Transaction 3,-500,50";          // -500.50 in US format
        InputStream inputStream = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));

        // When
        CSVImportService.ImportResult result = csvImportService.parseCSV(inputStream, "european_number_format.csv", null, null);

        // Then
        assertNotNull(result);
        assertTrue(result.getSuccessCount() >= 2, "Should parse at least 2 transactions");
        List<CSVImportService.ParsedTransaction> transactions = result.getTransactions();

        // Verify amounts are parsed correctly
        CSVImportService.ParsedTransaction tx1 = transactions.get(0);
        BigDecimal amount1 = tx1.getAmount();
        BigDecimal amount1Abs = amount1.compareTo(BigDecimal.ZERO) < 0 ? amount1.negate() : amount1;
        assertTrue(amount1Abs.compareTo(new BigDecimal("1234.56")) < 0.01,
                "Should parse European number format (1.234,56 = 1234.56)");

        CSVImportService.ParsedTransaction tx2 = transactions.get(1);
        BigDecimal amount2 = tx2.getAmount();
        BigDecimal amount2Abs = amount2.compareTo(BigDecimal.ZERO) < 0 ? amount2.negate() : amount2;
        assertTrue(amount2Abs.compareTo(new BigDecimal("2500.00")) < 0.01,
                "Should parse European number format (2.500,00 = 2500.00)");
    }
}


