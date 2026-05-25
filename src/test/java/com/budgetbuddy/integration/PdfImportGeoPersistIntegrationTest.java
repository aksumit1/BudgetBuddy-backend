package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.PDFImportService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.util.TableInitializer;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * End-to-end integration test for the PDF-import → DynamoDB persistence
 * pipeline. Validates that the new geo (city/state/country/postalCode/
 * phoneNumber/streetAddress), FX (originalCurrency*, exchangeRate), and
 * wallet fields survive the full path:
 *
 * <pre>
 *   PDFImportService.parsePDF()
 *     → ParsedTransaction
 *     → TransactionService.createTransaction()
 *     → (controller-style follow-up save with geo+FX+wallet)
 *     → TransactionRepository / DynamoDbEnhancedClient
 *     → DynamoDB (LocalStack)
 *     → TransactionRepository.findById()
 *     → TransactionTable with all fields populated
 * </pre>
 *
 * <p>This catches regressions like: forgetting to thread a new field through
 * the createTransaction signature, forgetting the {@code @DynamoDbAttribute}
 * annotation on a new getter, or accidentally clearing the field in the
 * existing-transaction-merge branch.
 *
 * <p>The test is gated on a system property pointing at a real PDF corpus
 * because end-to-end coverage requires a parseable real statement.
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "pdf.lbl.dir", matches = ".+")
class PdfImportGeoPersistIntegrationTest {

    private static final String CORPUS_DIR = System.getProperty(
            "pdf.lbl.dir", "/Users/garimaagarwal/Downloads/statements");

    @Autowired private PDFImportService pdfImportService;
    @Autowired private TransactionService transactionService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DynamoDbClient dynamoDbClient;

    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("e2e-" + UUID.randomUUID() + "@example.com");
        testUser.setPreferredCurrency("USD");
        userRepository.save(testUser);

        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("E2E Test Account");
        testAccount.setInstitutionName("Wells Fargo");
        testAccount.setAccountType("credit_card");
        accountRepository.save(testAccount);
    }

    @Test
    void wellsFargoPdf_geoFieldsRoundTripThroughDdb() throws Exception {
        final File pdf = new File(CORPUS_DIR, "011826 WellsFargo.pdf");
        assumeTrue(pdf.exists(), "corpus PDF not present: " + pdf);

        // Parse the PDF — produces ParsedTransaction rows with geo populated.
        final PDFImportService.ImportResult result;
        try (InputStream in = new FileInputStream(pdf)) {
            result = pdfImportService.parsePDF(in, pdf.getName(), testUser.getUserId(), null);
        }
        assertNotNull(result);
        assertTrue(result.getTransactions().size() > 0,
                "parse must produce at least one transaction");

        // Find a tx with geo populated — confirm at least one exists.
        final PDFImportService.ParsedTransaction parsedWithGeo = result.getTransactions().stream()
                .filter(t -> t.getCity() != null || t.getState() != null || t.getCountry() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected at least one parsed tx with geo, got none from "
                                + pdf.getName()));

        // Mirror what the controller does: createTransaction + follow-up
        // save for FX/geo/wallet (TransactionController.processPDFBatchImport).
        final TransactionTable created = transactionService.createTransaction(
                testUser, testAccount.getAccountId(),
                parsedWithGeo.getAmount(), parsedWithGeo.getDate(),
                parsedWithGeo.getDescription(),
                parsedWithGeo.getCategoryPrimary(), parsedWithGeo.getCategoryDetailed(),
                parsedWithGeo.getImporterCategoryPrimary(),
                parsedWithGeo.getImporterCategoryDetailed(),
                parsedWithGeo.getTransactionId(),
                null, null, null,
                parsedWithGeo.getTransactionType(),
                parsedWithGeo.getCurrencyCode(),
                "PDF", UUID.randomUUID().toString(), pdf.getName(),
                null,
                parsedWithGeo.getMerchantName(), parsedWithGeo.getLocation(),
                parsedWithGeo.getPaymentChannel(), parsedWithGeo.getUserName(),
                null, null);
        // Mirror controller's geo persistence step.
        created.setCity(parsedWithGeo.getCity());
        created.setState(parsedWithGeo.getState());
        created.setCountry(parsedWithGeo.getCountry());
        created.setPostalCode(parsedWithGeo.getPostalCode());
        created.setPhoneNumber(parsedWithGeo.getPhoneNumber());
        created.setStreetAddress(parsedWithGeo.getStreetAddress());
        transactionService.updateTransaction(created);

        // Read back via repository — bypasses any in-memory cache so this
        // truly proves DDB round-trip.
        final TransactionTable fetched = transactionRepository.findById(
                created.getTransactionId()).orElseThrow();
        assertEquals(parsedWithGeo.getCity(), fetched.getCity(),
                "city must round-trip through DDB");
        assertEquals(parsedWithGeo.getState(), fetched.getState(),
                "state must round-trip through DDB");
        assertEquals(parsedWithGeo.getCountry(), fetched.getCountry(),
                "country must round-trip through DDB");
        // Optional fields — only assert when source had them.
        if (parsedWithGeo.getPostalCode() != null) {
            assertEquals(parsedWithGeo.getPostalCode(), fetched.getPostalCode());
        }
        if (parsedWithGeo.getPhoneNumber() != null) {
            assertEquals(parsedWithGeo.getPhoneNumber(), fetched.getPhoneNumber());
        }
        if (parsedWithGeo.getStreetAddress() != null) {
            assertEquals(parsedWithGeo.getStreetAddress(), fetched.getStreetAddress());
        }
    }

    @Test
    void synthetic_allGeoAndFxFields_roundTripThroughDdb() {
        // No PDF needed — directly create a transaction with every new field
        // populated and assert the DDB schema preserves them.
        final TransactionTable created = transactionService.createTransaction(
                testUser, testAccount.getAccountId(),
                new java.math.BigDecimal("156.72"), java.time.LocalDate.of(2026, 4, 8),
                "THE WESTIN PUNE KOREGAON PARK PUNE",
                "TRAVEL", "HOTELS", null, null,
                UUID.randomUUID().toString(),
                null, null, null,
                "EXPENSE", "USD",
                "PDF", UUID.randomUUID().toString(), "synthetic.pdf",
                null,
                "THE WESTIN PUNE", "Pune, IN",
                null, null, null, null);
        created.setCity("Pune");
        created.setState(null);
        created.setCountry("IN");
        created.setPostalCode("411001");
        created.setPhoneNumber("912067656765");
        created.setStreetAddress("Koregaon Park 36/3-B");
        created.setOriginalCurrencyCode("INR");
        created.setOriginalCurrencyDisplay("INDIAN RUPEE");
        created.setOriginalAmount(new java.math.BigDecimal("14543.50"));
        created.setExchangeRate(new java.math.BigDecimal("0.010775948"));
        created.setWalletProvider("apple-pay");
        transactionService.updateTransaction(created);

        final TransactionTable fetched = transactionRepository.findById(
                created.getTransactionId()).orElseThrow();
        // Geo block
        assertEquals("Pune", fetched.getCity());
        assertEquals("IN", fetched.getCountry());
        assertEquals("411001", fetched.getPostalCode());
        assertEquals("912067656765", fetched.getPhoneNumber());
        assertEquals("Koregaon Park 36/3-B", fetched.getStreetAddress());
        // FX block
        assertEquals("INR", fetched.getOriginalCurrencyCode());
        assertEquals("INDIAN RUPEE", fetched.getOriginalCurrencyDisplay());
        assertEquals(0, new java.math.BigDecimal("14543.50")
                .compareTo(fetched.getOriginalAmount()),
                "original amount must round-trip with full precision");
        assertEquals(0, new java.math.BigDecimal("0.010775948")
                .compareTo(fetched.getExchangeRate()),
                "exchange rate must preserve 9-decimal precision");
        // Wallet
        assertEquals("apple-pay", fetched.getWalletProvider());
    }

    @Test
    void multipleTransactions_eachIndependentlyPersistsGeo() {
        // Persist 5 txs with distinct geo, fetch via user-id query, and
        // verify every row carries its own geo back (no clobbering).
        final String[][] rows = {
                {"COSTCO WHSE TUKWILA WA",  "Tukwila",   "WA", "US"},
                {"TIM HORTONS TORONTO ON",  "Toronto",   "ON", "CA"},
                {"PRET A MANGER LONDON GB", "London",    null, "GB"},
                {"WHOLE FOODS SEATTLE WA",  "Seattle",   "WA", "US"},
                {"STARBUCKS BELLEVUE WA",   "Bellevue",  "WA", "US"},
        };
        for (int i = 0; i < rows.length; i++) {
            final TransactionTable t = transactionService.createTransaction(
                    testUser, testAccount.getAccountId(),
                    new java.math.BigDecimal("10." + i + "0"),
                    java.time.LocalDate.of(2026, 5, i + 1),
                    rows[i][0], "OTHER", "OTHER", null, null,
                    UUID.randomUUID().toString(),
                    null, null, null, "EXPENSE", "USD",
                    "PDF", UUID.randomUUID().toString(), "multi.pdf",
                    null, rows[i][0], rows[i][1] + ", " + (rows[i][2] == null ? rows[i][3] : rows[i][2]),
                    null, null, null, null);
            t.setCity(rows[i][1]);
            t.setState(rows[i][2]);
            t.setCountry(rows[i][3]);
            transactionService.updateTransaction(t);
        }
        // Spot-check: fetch each one and confirm its geo is what we set.
        final List<TransactionTable> all =
                transactionService.getTransactions(testUser, 0, 100);
        long londonCount = all.stream().filter(t -> "London".equals(t.getCity())).count();
        long torontoCount = all.stream().filter(t -> "Toronto".equals(t.getCity())).count();
        long tukwilaCount = all.stream().filter(t -> "Tukwila".equals(t.getCity())).count();
        assertTrue(londonCount >= 1, "London tx must persist");
        assertTrue(torontoCount >= 1, "Toronto tx must persist");
        assertTrue(tukwilaCount >= 1, "Tukwila tx must persist");
        // Verify each row's state/country is independent — no clobbering
        // between rows.
        all.stream()
                .filter(t -> "Toronto".equals(t.getCity()))
                .forEach(t -> {
                    assertEquals("ON", t.getState());
                    assertEquals("CA", t.getCountry());
                });
        all.stream()
                .filter(t -> "London".equals(t.getCity()))
                .forEach(t -> {
                    assertEquals("GB", t.getCountry());
                });
    }
}
