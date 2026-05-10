package com.budgetbuddy.api;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Integration Tests for PDF Import Account Association
 *
 * <p>Tests the bug fix where transactions from multiple PDF imports were incorrectly associated
 * with the first account instead of their respective detected accounts.
 *
 * <p>Test scenarios: 1. Import PDF 1 - should create account 1 and associate transactions with it
 * 2. Import PDF 2 - should create account 2 and associate transactions with it (not account 1) 3.
 * Verify transactions are correctly associated with their respective accounts
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PDFImportAccountAssociationTest {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(PDFImportAccountAssociationTest.class);

    @Autowired private TransactionController transactionController;

    @Autowired private AccountRepository accountRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private UserService userService;

    @Autowired private DynamoDbClient dynamoDbClient;

    @Autowired private PDFImportService pdfImportService;

    private UserTable testUser;
    private UserDetails userDetails;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        // Create test user
        final String email = "test-pdf-account-" + UUID.randomUUID() + "@example.com";
        final String passwordHash = java.util.Base64.getEncoder().encodeToString("test-hash".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(email, passwordHash, "Test", "User");

        // Mock UserDetails
        userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn(email);
    }

    @Test
    void testPDFImportTwoDifferentPDFsTransactionsAssociatedWithCorrectAccounts()
            throws Exception {
        // Given: Two different PDFs with different account information

        // PDF 1: Chase Checking account
        final byte[] pdf1Content =
                createPDFWithAccountInfo(
                        "Chase Bank Statement",
                        "Account Number: ****1234",
                        "Account Type: Checking",
                        "01/15/2025\tDEPOSIT\t$1000.00",
                        "01/16/2025\tGROCERY STORE\t-$50.00");

        // PDF 2: Wells Fargo Savings account (different account)
        final byte[] pdf2Content =
                createPDFWithAccountInfo(
                        "Wells Fargo Statement",
                        "Account Number: ****5678",
                        "Account Type: Savings",
                        "01/20/2025\tDEPOSIT\t$2000.00",
                        "01/21/2025\tRESTAURANT\t-$75.00");

        // When: Import PDF 1
        LOGGER.info("📄 Importing PDF 1 (Chase Checking)");
        final org.springframework.mock.web.MockMultipartFile pdf1File =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "chase_checking_1234.pdf", "application/pdf", pdf1Content);
        final ResponseEntity<com.budgetbuddy.api.TransactionController.BatchImportResponse> response1 =
                transactionController.importPDF(
                        userDetails, pdf1File, null, "chase_checking_1234.pdf");

        assertEquals(200, response1.getStatusCodeValue());
        assertNotNull(response1.getBody());
        assertTrue(response1.getBody().getCreated() > 0, "Should create transactions from PDF 1");

        // Get account 1 that was created
        final List<AccountTable> accountsAfterPdf1 = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(
                1, accountsAfterPdf1.size(), "Should have created exactly 1 account after PDF 1");
        final AccountTable account1 = accountsAfterPdf1.get(0);
        final String account1Id = account1.getAccountId();
        LOGGER.info(
                "✅ Account 1 created: ID='{}', Name='{}', Institution='{}', Number='{}'",
                account1Id,
                account1.getAccountName(),
                account1.getInstitutionName(),
                account1.getAccountNumber());

        // Verify transactions from PDF 1 are associated with account 1
        final List<TransactionTable> transactionsFromPdf1 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 1000);
        assertFalse(transactionsFromPdf1.isEmpty(), "Should have transactions from PDF 1");
        for (final TransactionTable tx : transactionsFromPdf1) {
            assertEquals(
                    account1Id,
                    tx.getAccountId(),
                    "Transaction from PDF 1 should be associated with account 1: "
                            + tx.getDescription());
        }
        LOGGER.info(
                "✅ Verified {} transactions from PDF 1 are associated with account 1",
                transactionsFromPdf1.size());

        // When: Import PDF 2 (different account)
        LOGGER.info("📄 Importing PDF 2 (Wells Fargo Savings)");
        final org.springframework.mock.web.MockMultipartFile pdf2File =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "wells_fargo_savings_5678.pdf", "application/pdf", pdf2Content);
        final ResponseEntity<com.budgetbuddy.api.TransactionController.BatchImportResponse> response2 =
                transactionController.importPDF(
                        userDetails, pdf2File, null, "wells_fargo_savings_5678.pdf");

        assertEquals(200, response2.getStatusCodeValue());
        assertNotNull(response2.getBody());
        assertTrue(response2.getBody().getCreated() > 0, "Should create transactions from PDF 2");

        // Get accounts after PDF 2
        final List<AccountTable> accountsAfterPdf2 = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(
                2, accountsAfterPdf2.size(), "Should have created exactly 2 accounts after PDF 2");

        // Find account 2 (the new one)
        final AccountTable account2 =
                accountsAfterPdf2.stream()
                        .filter(acc -> !acc.getAccountId().equals(account1Id))
                        .findFirst()
                        .orElse(null);
        assertNotNull(account2, "Account 2 should exist");
        final String account2Id = account2.getAccountId();
        LOGGER.info(
                "✅ Account 2 created: ID='{}', Name='{}', Institution='{}', Number='{}'",
                account2Id,
                account2.getAccountName(),
                account2.getInstitutionName(),
                account2.getAccountNumber());

        // Verify account 2 is different from account 1
        assertNotEquals(account1Id, account2Id, "Account 2 should be different from account 1");

        // Get all transactions
        final List<TransactionTable> allTransactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 1000);
        assertFalse(allTransactions.isEmpty(), "Should have transactions from both PDFs");

        // Separate transactions by account
        final List<TransactionTable> account1Transactions =
                allTransactions.stream()
                        .filter(tx -> account1Id.equals(tx.getAccountId()))
                        .collect(Collectors.toList());
        final List<TransactionTable> account2Transactions =
                allTransactions.stream()
                        .filter(tx -> account2Id.equals(tx.getAccountId()))
                        .collect(Collectors.toList());

        LOGGER.info(
                "📊 Transaction distribution: Account1={}, Account2={}, Total={}",
                account1Transactions.size(),
                account2Transactions.size(),
                allTransactions.size());

        // Then: Verify transactions are correctly associated
        assertFalse(account1Transactions.isEmpty(), "Account 1 should have transactions");
        assertFalse(account2Transactions.isEmpty(), "Account 2 should have transactions");

        // CRITICAL: Verify no transactions from PDF 2 are associated with account 1
        // (This was the bug - transactions from PDF 2 were going to account 1)
        for (final TransactionTable tx : account2Transactions) {
            assertNotEquals(
                    account1Id,
                    tx.getAccountId(),
                    "Transaction from PDF 2 should NOT be associated with account 1: "
                            + tx.getDescription());
            assertEquals(
                    account2Id,
                    tx.getAccountId(),
                    "Transaction from PDF 2 should be associated with account 2: "
                            + tx.getDescription());
        }

        // Verify transactions from PDF 1 are still with account 1
        for (final TransactionTable tx : account1Transactions) {
            assertEquals(
                    account1Id,
                    tx.getAccountId(),
                    "Transaction from PDF 1 should still be associated with account 1: "
                            + tx.getDescription());
            assertNotEquals(
                    account2Id,
                    tx.getAccountId(),
                    "Transaction from PDF 1 should NOT be associated with account 2: "
                            + tx.getDescription());
        }

        LOGGER.info(
                "✅ SUCCESS: Transactions correctly associated - Account1: {}, Account2: {}",
                account1Transactions.size(),
                account2Transactions.size());
    }

    @Test
    void testPDFImportSameAccountTwiceReusesExistingAccount() throws Exception {
        // Given: Two PDFs with the same account information
        final byte[] pdf1Content =
                createPDFWithAccountInfo(
                        "Chase Bank Statement",
                        "Account Number: ****1234",
                        "Account Type: Checking",
                        "01/15/2025\tDEPOSIT\t$1000.00");

        final byte[] pdf2Content =
                createPDFWithAccountInfo(
                        "Chase Bank Statement",
                        "Account Number: ****1234",
                        "Account Type: Checking",
                        "01/20/2025\tRESTAURANT\t-$50.00");

        // When: Import PDF 1
        final org.springframework.mock.web.MockMultipartFile pdf1File =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "chase_checking_1234.pdf", "application/pdf", pdf1Content);
        transactionController.importPDF(userDetails, pdf1File, null, "chase_checking_1234.pdf");

        // Get account 1
        final List<AccountTable> accountsAfterPdf1 = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(1, accountsAfterPdf1.size());
        final String account1Id = accountsAfterPdf1.get(0).getAccountId();

        // When: Import PDF 2 (same account)
        final org.springframework.mock.web.MockMultipartFile pdf2File =
                new org.springframework.mock.web.MockMultipartFile(
                        "file", "chase_checking_1234.pdf", "application/pdf", pdf2Content);
        transactionController.importPDF(userDetails, pdf2File, null, "chase_checking_1234.pdf");

        // Then: Should still have only 1 account (reused)
        final List<AccountTable> accountsAfterPdf2 = accountRepository.findByUserId(testUser.getUserId());
        assertEquals(
                1, accountsAfterPdf2.size(), "Should reuse existing account, not create duplicate");
        assertEquals(account1Id, accountsAfterPdf2.get(0).getAccountId(), "Should reuse account 1");

        // Verify all transactions are associated with the same account
        final List<TransactionTable> allTransactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 1000);
        for (final TransactionTable tx : allTransactions) {
            assertEquals(
                    account1Id,
                    tx.getAccountId(),
                    "All transactions should be associated with the same account: "
                            + tx.getDescription());
        }
    }

    /** Create a simple PDF with account information and transactions */
    private byte[] createPDFWithAccountInfo(
            final String title, final String accountInfo, final String accountType, final String... transactions)
            throws Exception {
        // Build text content with account info and transactions
        final StringBuilder textContent = new StringBuilder();
        textContent.append(title).append("\n");
        textContent.append(accountInfo).append("\n");
        textContent.append(accountType).append("\n");
        textContent.append("Date\tDescription\tAmount\n");
        for (final String tx : transactions) {
            textContent.append(tx).append("\n");
        }

        return createPDFFromText(textContent.toString());
    }

    /** Create a PDF from text content (similar to PDFImportIntegrationTest) */
    private byte[] createPDFFromText(final String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            final PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                final PDFont font = new PDType1Font(Standard14Fonts.FontName.COURIER);
                contentStream.setFont(font, 10);
                contentStream.setLeading(12);
                contentStream.newLineAtOffset(25, 750);

                final String[] lines = text.split("\n");
                for (final String line : lines) {
                    if (line != null && !line.isBlank()) {
                        // Replace tabs with spaces (Courier font doesn't support tab characters)
                        final String cleanedLine = line.trim().replace('\t', ' ');
                        contentStream.showText(cleanedLine);
                        contentStream.newLine();
                    }
                }

                contentStream.endText();
            }

            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            document.close();

            return baos.toByteArray();
        }
    }
}
