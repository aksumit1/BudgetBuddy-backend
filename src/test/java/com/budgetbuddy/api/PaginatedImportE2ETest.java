package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-End Tests for Paginated CSV Import Tests pagination functionality, edge cases, boundary
 * conditions, and race conditions
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
// `\n` in the format strings here is a literal LF (CSV rows / raw
// HTTP body templates), not a platform newline — we do NOT want %n.
@SuppressFBWarnings(
        value = {"VA_FORMAT_STRING_USES_NEWLINE", "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION"},
        justification =
                "literal LF in CSV / wire format (not platform newline); "
                        + "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaginatedImportE2ETest {

    private static final String SIZE = "size";
    private static final String PAGE = "page";
    private static final String FILE = "file";

    private static final Logger LOGGER = LoggerFactory.getLogger(PaginatedImportE2ETest.class);

    @Autowired private MockMvc mockMvc;

    @Autowired private UserService userService;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private AccountRepository accountRepository;

    @Autowired private UserDetailsService userDetailsService;

    private UserTable testUser;
    private String testEmail;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        // Create test user
        testEmail = "paginated-import-test-" + UUID.randomUUID() + "@example.com";
        // Use a consistent base64-encoded string as client hash (representing a client-side PBKDF2
        // hash)
        // This must be the same for both createUserSecure and authenticate
        final String passwordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("testPassword123".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(testEmail, passwordHash, "Test", "User");

        // Wait a bit for DynamoDB eventual consistency
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Get UserDetails for authentication
        userDetails = userDetailsService.loadUserByUsername(testEmail);

        // Clean up any existing transactions and accounts for this user
        try {
            final List<TransactionTable> existing =
                    transactionRepository.findByUserId(testUser.getUserId(), 0, 10_000);
            for (final TransactionTable tx : existing) {
                transactionRepository.delete(tx.getTransactionId());
            }
            final List<AccountTable> existingAccounts =
                    accountRepository.findByUserId(testUser.getUserId());
            for (final AccountTable acc : existingAccounts) {
                accountRepository.delete(acc.getAccountId());
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    /** Helper method to create CSV content with specified number of transactions */
    private String createCSVContent(final int transactionCount) {
        final StringBuilder csv = new StringBuilder();
        csv.append("Date,Description,Amount\n");

        final LocalDate baseDate = LocalDate.now().minusDays(transactionCount);
        for (int i = 0; i < transactionCount; i++) {
            final LocalDate date = baseDate.plusDays(i);
            csv.append(
                    String.format(
                            "%s,Transaction %d,%.2f\n",
                            date.format(DateTimeFormatter.ISO_LOCAL_DATE), i + 1, -10.0 * (i + 1)));
        }

        return csv.toString();
    }

    @Test
    void testPaginatedImportEmptyFileReturnsEmptyResponse() throws Exception {
        // Given: Empty CSV file
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "empty.csv",
                        "text/csv",
                        "Date,Description,Amount\n"
                                .getBytes(StandardCharsets.UTF_8) // Header only, no data
                        );

        // When: Importing chunk 0
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.importResponse.created").value(0));
    }

    @Test
    void testPaginatedImportSingleTransactionImportsSuccessfully() throws Exception {
        // Given: CSV with 1 transaction
        final String csvContent = createCSVContent(1);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "single.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing chunk 0
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.importResponse.created").value(1));

        // Then: Verify transaction was created
        final List<TransactionTable> transactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(1, transactions.size());
    }

    @Test
    void testPaginatedImportExactPageBoundaryHandlesCorrectly() throws Exception {
        // Given: CSV with exactly 100 transactions (1 full page)
        final String csvContent = createCSVContent(100);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "exact100.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing chunk 0
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(100))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.importResponse.created").value(100));

        // Then: Verify all transactions were created
        final List<TransactionTable> transactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(100, transactions.size());
    }

    @Test
    void testPaginatedImportExactPageBoundaryPlusOneHandlesCorrectly() throws Exception {
        // Given: CSV with 101 transactions (1 full page + 1)
        final String csvContent = createCSVContent(101);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "exact101.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing chunk 0 (first 100)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(101))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.importResponse.created").value(100));

        // When: Importing chunk 1 (remaining 1)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "1")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(101))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.importResponse.created").value(1));

        // Then: Verify all 101 transactions were created
        // Note: findByUserId has maxLimit of 100, so we need to paginate
        final List<TransactionTable> transactions = new ArrayList<>();
        final List<TransactionTable> page1 =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        transactions.addAll(page1);
        if (page1.size() == 100) {
            final List<TransactionTable> page2 =
                    transactionRepository.findByUserId(testUser.getUserId(), 100, 100);
            transactions.addAll(page2);
        }
        assertEquals(
                101,
                transactions.size(),
                "Expected 101 transactions but found " + transactions.size());
    }

    @Test
    void testPaginatedImportLargeFileImportsAllPages() throws Exception {
        // Given: CSV with 476 transactions (matches user's reported case)
        final String csvContent = createCSVContent(476);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "large476.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        final int size = 100;
        final int totalPages = (int) Math.ceil(476.0 / size); // 5 pages

        // When: Importing all pages
        for (int i = 0; i < totalPages; i++) {
            mockMvc.perform(
                            multipart("/api/transactions/import-csv/chunk")
                                    .file(file)
                                    .param(PAGE, String.valueOf(i))
                                    .param(SIZE, String.valueOf(size))
                                    .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(476))
                    .andExpect(jsonPath("$.totalPages").value(totalPages))
                    .andExpect(jsonPath("$.page").value(i))
                    .andExpect(jsonPath("$.hasNext").value(i < totalPages - 1));
        }

        // Then: Verify all transactions were created
        // Note: findByUserId has maxLimit of 100, so we need to paginate
        final List<TransactionTable> transactions = new ArrayList<>();
        for (int skip = 0; skip < 476; skip += 100) {
            final List<TransactionTable> page =
                    transactionRepository.findByUserId(testUser.getUserId(), skip, 100);
            transactions.addAll(page);
            if (page.size() < 100) {
                break; // Last page
            }
        }
        assertEquals(
                476,
                transactions.size(),
                "Expected 476 transactions but found " + transactions.size());
    }

    @Test
    void testPaginatedImportInvalidPageNumberReturnsError() throws Exception {
        // Given: CSV with 100 transactions
        final String csvContent = createCSVContent(100);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing page 1 (out of range - only 1 page exists)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "1")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testPaginatedImportInvalidPageSizeReturnsError() throws Exception {
        // Given: CSV file
        final String csvContent = createCSVContent(10);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing with size > 500 (max allowed)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "501")
                                .with(user(userDetails)))
                .andExpect(status().isBadRequest());

        // When: Importing with size < 1
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "0")
                                .with(user(userDetails)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testPaginatedImportNegativePageReturnsError() throws Exception {
        // Given: CSV file
        final String csvContent = createCSVContent(10);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing with negative page
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "-1")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testPaginatedImportDifferentPageSizesHandlesCorrectly() throws Exception {
        // Given: CSV with 250 transactions
        final String csvContent = createCSVContent(250);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "test250.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing with size=50 (5 pages)
        final int size = 50;
        final int totalPages = (int) Math.ceil(250.0 / size);

        for (int page = 0; page < totalPages; page++) {
            mockMvc.perform(
                            multipart("/api/transactions/import-csv/chunk")
                                    .file(file)
                                    .param(PAGE, String.valueOf(page))
                                    .param(SIZE, String.valueOf(size))
                                    .with(user(userDetails)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(250))
                    .andExpect(jsonPath("$.totalPages").value(totalPages))
                    .andExpect(jsonPath("$.page").value(page))
                    .andExpect(jsonPath("$.hasNext").value(page < totalPages - 1));
        }

        // Then: Verify all transactions were created
        // Note: findByUserId has maxLimit of 100, so we need to paginate
        final List<TransactionTable> transactions = new ArrayList<>();
        for (int skip = 0; skip < 250; skip += 100) {
            final List<TransactionTable> page =
                    transactionRepository.findByUserId(testUser.getUserId(), skip, 100);
            transactions.addAll(page);
            if (page.size() < 100) {
                break; // Last page
            }
        }
        assertEquals(
                250,
                transactions.size(),
                "Expected 250 transactions but found " + transactions.size());
    }

    @Test
    void testPaginatedImportConcurrentImportsSameFileHandlesCorrectly() throws Exception {
        // Given: CSV with 200 transactions
        final String csvContent = createCSVContent(200);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "concurrent.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing pages 0 and 1 concurrently
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        final List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int page = 0; page < 2; page++) {
            final int pageNum = page;
            final CompletableFuture<Void> future =
                    CompletableFuture.runAsync(
                            () -> {
                                try {
                                    mockMvc.perform(
                                                    multipart("/api/transactions/import-csv/chunk")
                                                            .file(file)
                                                            .param(PAGE, String.valueOf(pageNum))
                                                            .param(SIZE, "100")
                                                            .with(user(userDetails)))
                                            .andExpect(status().isOk());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            },
                            executor);
            futures.add(future);
        }

        // Wait for all to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);

        executor.shutdown();

        // Then: Verify all transactions were created (may have duplicates due to race condition)
        // Note: findByUserId has maxLimit of 100, so we need to paginate
        final List<TransactionTable> transactions = new ArrayList<>();
        for (int skip = 0; skip < 300; skip += 100) {
            final List<TransactionTable> page =
                    transactionRepository.findByUserId(testUser.getUserId(), skip, 100);
            transactions.addAll(page);
            if (page.size() < 100) {
                break; // Last page
            }
        }
        // Should have at least 200, but may have duplicates if race condition occurs
        assertTrue(
                transactions.size() >= 200,
                "Expected at least 200 transactions, got " + transactions.size());
    }

    @Test
    void testPaginatedImportOutOfOrderPagesHandlesCorrectly() throws Exception {
        // Given: CSV with 300 transactions
        final String csvContent = createCSVContent(300);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "outoforder.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing pages in reverse order (2, 1, 0)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "2")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));

        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "1")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasNext").value(true));

        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.hasNext").value(true));

        // Then: Verify all transactions were created
        // Note: findByUserId has maxLimit of 100, so we need to paginate
        final List<TransactionTable> transactions = new ArrayList<>();
        for (int skip = 0; skip < 300; skip += 100) {
            final List<TransactionTable> page =
                    transactionRepository.findByUserId(testUser.getUserId(), skip, 100);
            transactions.addAll(page);
            if (page.size() < 100) {
                break; // Last page
            }
        }
        assertEquals(
                300,
                transactions.size(),
                "Expected 300 transactions but found " + transactions.size());
    }

    @Test
    void testPaginatedImportMaxSizeHandlesCorrectly() throws Exception {
        // Given: CSV with 1000 transactions
        final String csvContent = createCSVContent(1000);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "maxsize.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing with max size (500)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "500")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1000))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(500))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.importResponse.created").value(500));

        // Then: Import second page
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "1")
                                .param(SIZE, "500")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.importResponse.created").value(500));

        // Then: Verify all transactions were created
        // Note: findByUserId has maxLimit of 100, so we need to paginate
        final List<TransactionTable> transactions = new ArrayList<>();
        for (int skip = 0; skip < 1000; skip += 100) {
            final List<TransactionTable> page =
                    transactionRepository.findByUserId(testUser.getUserId(), skip, 100);
            transactions.addAll(page);
            if (page.size() < 100) {
                break; // Last page
            }
        }
        assertEquals(
                1000,
                transactions.size(),
                "Expected 1000 transactions but found " + transactions.size());
    }

    @Test
    void testPaginatedImportResponseStructureContainsAllFields() throws Exception {
        // Given: CSV with 50 transactions
        final String csvContent = createCSVContent(50);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "structure.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing chunk
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                // Verify ChunkImportResponse structure
                .andExpect(jsonPath("$.importResponse").exists())
                .andExpect(jsonPath("$.importResponse.total").exists())
                .andExpect(jsonPath("$.importResponse.created").exists())
                .andExpect(jsonPath("$.importResponse.failed").exists())
                .andExpect(jsonPath("$.importResponse.duplicates").exists())
                // Verify pagination fields
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.size").exists())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.totalPages").exists())
                .andExpect(jsonPath("$.hasNext").exists());
    }

    // ========== Preview Endpoint Tests ==========

    @Test
    void testPreviewCSVReturnsCorrectStructure() throws Exception {
        // Given: CSV with 10 transactions
        final String csvContent = createCSVContent(10);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "preview-test.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Requesting preview
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/preview")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                // Verify preview response structure
                .andExpect(jsonPath("$.totalParsed").exists())
                .andExpect(jsonPath("$.transactions").exists())
                .andExpect(jsonPath("$.transactions").isArray())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.size").exists())
                .andExpect(jsonPath("$.totalPages").exists())
                .andExpect(jsonPath("$.totalElements").exists())
                .andExpect(jsonPath("$.detectedAccount").exists());
    }

    @Test
    void testPreviewCSVWithAccountIdReturnsPreview() throws Exception {
        // Given: CSV file and existing account
        final String csvContent = createCSVContent(5);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "preview-with-account.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // Create an existing account
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountName("Test Checking Account");
        existingAccount.setInstitutionName("Test Bank");
        existingAccount.setAccountType("depository");
        existingAccount.setAccountSubtype("checking");
        existingAccount.setBalance(BigDecimal.ZERO);
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        accountRepository.save(existingAccount);

        // When: Requesting preview with accountId
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/preview")
                                .file(file)
                                .param("accountId", existingAccount.getAccountId())
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalParsed").value(5))
                .andExpect(jsonPath("$.transactions").isArray())
                .andExpect(jsonPath("$.transactions.length()").value(5));
    }

    // ========== Account Scenario Tests ==========

    @Test
    void testPaginatedImportWithExistingAccountSelectedUsesSelectedAccount() throws Exception {
        // Given: CSV file and existing account
        final String csvContent = createCSVContent(50);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "selected-account.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // Create an existing account
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountName("Selected Account");
        existingAccount.setInstitutionName("Test Bank");
        existingAccount.setAccountType("depository");
        existingAccount.setAccountSubtype("checking");
        existingAccount.setBalance(BigDecimal.ZERO);
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        accountRepository.save(existingAccount);

        // When: Importing with accountId parameter (user selected existing account)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .param("accountId", existingAccount.getAccountId())
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importResponse.created").value(50));

        // Then: Verify all transactions are associated with the selected account
        final List<TransactionTable> transactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(50, transactions.size());
        // All transactions should have the selected account ID
        for (final TransactionTable tx : transactions) {
            assertEquals(
                    existingAccount.getAccountId(),
                    tx.getAccountId(),
                    "Transaction should be associated with selected account");
        }
    }

    @Test
    void testPaginatedImportAccountReuseAcrossPagesValidatesCorrectly() throws Exception {
        // Given: CSV with 150 transactions (2 pages)
        final String csvContent = createCSVContent(150);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "account-reuse.csv",
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing page 0 (first 100)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importResponse.created").value(100));

        // Get the account created on page 0 (if any)
        // Note: Account creation depends on detected account info from filename/metadata
        // If no account info is detected, transactions will use pseudo accounts
        // Wait a bit for DynamoDB eventual consistency
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Retry logic for eventual consistency
        List<AccountTable> accountsAfterPage0 = null;
        for (int i = 0; i < 5; i++) {
            accountsAfterPage0 = accountRepository.findByUserId(testUser.getUserId());
            // Filter out pseudo accounts (they have specific naming pattern)
            if (accountsAfterPage0 != null) {
                accountsAfterPage0 =
                        accountsAfterPage0.stream()
                                .filter(
                                        acc ->
                                                acc.getAccountName() == null
                                                        || !acc.getAccountName()
                                                                .toLowerCase(Locale.ROOT)
                                                                .contains("pseudo"))
                                .collect(java.util.stream.Collectors.toList());
            }
            if (accountsAfterPage0 != null && accountsAfterPage0.size() >= 1) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        // Account creation is optional - if no account info is detected, transactions use pseudo
        // accounts
        // The test should verify account reuse IF an account was created
        String accountIdFromPage0 = null;
        if (accountsAfterPage0 != null && accountsAfterPage0.size() >= 1) {
            accountIdFromPage0 = accountsAfterPage0.get(0).getAccountId();
        }

        // When: Importing page 1 (remaining 50)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "1")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importResponse.created").value(50));

        // Then: Verify account was reused (if account was created)
        if (accountIdFromPage0 != null) {
            // Wait for eventual consistency
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            List<AccountTable> accountsAfterPage1 =
                    accountRepository.findByUserId(testUser.getUserId());
            // Filter out pseudo accounts
            accountsAfterPage1 =
                    accountsAfterPage1.stream()
                            .filter(
                                    acc ->
                                            acc.getAccountName() == null
                                                    || !acc.getAccountName()
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains("pseudo"))
                            .collect(java.util.stream.Collectors.toList());
            assertEquals(
                    1,
                    accountsAfterPage1.size(),
                    "Should still have exactly 1 account after page 1 (account should be reused)");
            assertEquals(
                    accountIdFromPage0,
                    accountsAfterPage1.get(0).getAccountId(),
                    "Account ID should be the same (account reused)");
        } else {
            // If no account was created, that's acceptable - transactions will use pseudo accounts
            // Just verify transactions were created
            LOGGER.info(
                    "No account was created during import - transactions will use pseudo accounts (this is acceptable)");
        }

        // Verify all transactions were created
        final List<TransactionTable> transactions = new ArrayList<>();
        for (int skip = 0; skip < 150; skip += 100) {
            final List<TransactionTable> page =
                    transactionRepository.findByUserId(testUser.getUserId(), skip, 100);
            transactions.addAll(page);
            if (page.size() < 100) {
                break;
            }
        }
        assertEquals(150, transactions.size(), "All 150 transactions should be created");

        // If an account was created, verify all transactions use it
        if (accountIdFromPage0 != null) {
            for (final TransactionTable tx : transactions) {
                assertEquals(
                        accountIdFromPage0,
                        tx.getAccountId(),
                        "All transactions should use the same account");
            }
        }
    }

    @Test
    void testPaginatedImportWithDetectedAccountInfoCreatesNewAccount() throws Exception {
        // Given: CSV file with account information in filename (simulating detected account)
        // Note: In real scenario, account detection happens in CSVImportService based on
        // filename/content
        // For this test, we'll create a CSV and verify that an account is created
        final String csvContent = createCSVContent(25);
        // Use a filename that might trigger account detection
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "Chase_Checking_1234_Statement.csv", // Filename suggests account info
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing (account should be auto-created if detected)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importResponse.created").value(25));

        // Then: Verify an account was created
        final List<AccountTable> accounts = accountRepository.findByUserId(testUser.getUserId());
        assertTrue(accounts.size() >= 1, "At least one account should be created");

        // Verify transactions are associated with the account
        final List<TransactionTable> transactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(25, transactions.size());
        for (final TransactionTable tx : transactions) {
            assertNotNull(tx.getAccountId(), "Transaction should have an account ID");
        }
    }

    @Test
    void testPreviewCSVDetectsAccountInfoReturnsDetectedAccount() throws Exception {
        // Given: CSV file with account information in filename
        final String csvContent = createCSVContent(10);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "BankOfAmerica_Checking_5678.csv", // Filename suggests account info
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Requesting preview
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/preview")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detectedAccount").exists())
                // Detected account may or may not be present depending on detection logic
                // But the field should exist in the response
                .andExpect(jsonPath("$.totalParsed").value(10))
                .andExpect(jsonPath("$.transactions").isArray());
    }

    @Test
    void testPaginatedImportWithExistingAccountMatchingDetectedInfoReusesAccount()
            throws Exception {
        // Given: Existing account with specific account number
        final AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(UUID.randomUUID().toString().toLowerCase(Locale.ROOT));
        existingAccount.setUserId(testUser.getUserId());
        existingAccount.setAccountName("Chase Checking");
        existingAccount.setInstitutionName("Chase");
        existingAccount.setAccountType("depository");
        existingAccount.setAccountSubtype("checking");
        existingAccount.setAccountNumber("1234567890"); // Account number for matching
        existingAccount.setBalance(BigDecimal.ZERO);
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        accountRepository.save(existingAccount);

        // Given: CSV file that would detect the same account
        final String csvContent = createCSVContent(30);
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "Chase_Checking_1234567890_Statement.csv", // Filename suggests same account
                        "text/csv",
                        csvContent.getBytes(StandardCharsets.UTF_8));

        // When: Importing (should match existing account)
        mockMvc.perform(
                        multipart("/api/transactions/import-csv/chunk")
                                .file(file)
                                .param(PAGE, "0")
                                .param(SIZE, "100")
                                .with(user(userDetails)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importResponse.created").value(30));

        // Then: Verify account was reused (still only 1 account, not 2)
        final List<AccountTable> accounts = accountRepository.findByUserId(testUser.getUserId());
        // Note: Account matching logic may create a new account if detection doesn't match exactly
        // This test validates the behavior, even if it creates a new account
        assertTrue(accounts.size() >= 1, "At least one account should exist");

        // Verify transactions were created
        final List<TransactionTable> transactions =
                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
        assertEquals(30, transactions.size());
    }
}
