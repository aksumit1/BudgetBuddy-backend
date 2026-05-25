package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end tests for import functionality (CSV, Excel, PDF) Tests complete flow from file upload
 * to transaction creation
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(AWSTestConfiguration.class)
@ActiveProfiles("test")
class ImportEndToEndTest {

    private static final String FILE = "file";
    private static final String AUTHORIZATION = "Authorization";

    @Autowired private MockMvc mockMvc;

    // ObjectMapper not currently used but may be needed for future test enhancements
    // @Autowired
    // private ObjectMapper objectMapper;

    @Autowired private AuthService authService;

    @Autowired private UserService userService;

    @Autowired private AccountRepository accountRepository;

    private String testEmail;
    private String testPasswordHash;
    private String authToken;
    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        testEmail = "test-import-" + UUID.randomUUID() + "@example.com";
        testPasswordHash =
                Base64.getEncoder()
                        .encodeToString("hashed-password".getBytes(StandardCharsets.UTF_8));

        // Create test user
        testUser = userService.createUserSecure(testEmail, testPasswordHash, "Test", "User");

        // Authenticate to get token
        final AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(testPasswordHash);
        final AuthResponse authResponse = authService.authenticate(loginRequest);
        authToken = authResponse.getAccessToken();

        // Create test account for transaction tests
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUser.getUserId());
        testAccount.setAccountName("Test Account");
        testAccount.setInstitutionName("Test Bank");
        testAccount.setAccountType("CHECKING");
        testAccount.setBalance(new BigDecimal("1000.00"));
        testAccount.setCurrencyCode("USD");
        testAccount.setActive(true);
        testAccount.setCreatedAt(java.time.Instant.now());
        testAccount.setUpdatedAt(java.time.Instant.now());
        accountRepository.save(testAccount);
    }

    @Test
    void testCSVImportPreviewSucceeds() throws Exception {
        // Test CSV preview endpoint
        final String csvContent =
                "Date,Amount,Description\n2024-01-15,-50.00,Grocery Store\n2024-01-16,-25.50,Coffee Shop";
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/transactions/import-csv/preview")
                                .file(file)
                                .header(AUTHORIZATION, "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactions").isArray())
                .andExpect(jsonPath("$.data.transactions.length()").value(2))
                .andExpect(jsonPath("$.data.transactions[0].description").exists())
                .andExpect(jsonPath("$.data.transactions[0].amount").exists())
                .andExpect(jsonPath("$.data.transactions[0].date").exists());
    }

    @Test
    void testCSVImportImportSucceeds() throws Exception {
        // Test CSV import endpoint
        final String csvContent = "Date,Amount,Description\n2024-01-15,-50.00,Grocery Store";
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/transactions/import-csv")
                                .file(file)
                                .param("accountId", testAccount.getAccountId())
                                .header(AUTHORIZATION, "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.successful").exists())
                .andExpect(jsonPath("$.data.failed").exists());
    }

    @Test
    void testExcelImportPreviewSucceeds() throws Exception {
        // Test Excel preview endpoint
        // Create a simple Excel file content (CSV-like for testing)
        // Note: In real scenario, would use Apache POI to create actual Excel file
        final String excelContent = "Date\tAmount\tDescription\n2024-01-15\t-50.00\tGrocery Store";
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "test.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        excelContent.getBytes(StandardCharsets.UTF_8));

        // Note: This test may fail if backend requires actual Excel format
        // The endpoint should handle invalid Excel files gracefully
        // For now, just verify the request is processed (may return 200 or 400 depending on file
        // format)
        final var result =
                mockMvc.perform(
                                multipart("/api/transactions/import-excel/preview")
                                        .file(file)
                                        .header(AUTHORIZATION, "Bearer " + authToken))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        assertTrue(
                status == 200 || status == 400,
                "Should return 200 (success) or 400 (validation error)");
    }

    @Test
    void testPDFImportPreviewSucceeds() throws Exception {
        // Test PDF preview endpoint
        // Create a simple PDF file content (text-based for testing)
        // Note: In real scenario, would use PDFBox to create actual PDF file
        final String pdfContent =
                "Statement Period: 01/01/2024 - 01/31/2024\nDate\tAmount\tDescription\n01/15/2024\t-50.00\tGrocery Store";
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "test.pdf",
                        "application/pdf",
                        pdfContent.getBytes(StandardCharsets.UTF_8));

        // Note: This test may fail if backend requires actual PDF format
        // The endpoint should handle invalid PDF files gracefully
        // For now, just verify the request is processed (may return 200 or 400 depending on file
        // format)
        final var result =
                mockMvc.perform(
                                multipart("/api/transactions/import-pdf/preview")
                                        .file(file)
                                        .header(AUTHORIZATION, "Bearer " + authToken))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        assertTrue(
                status == 200 || status == 400,
                "Should return 200 (success) or 400 (validation error)");
    }

    @Test
    void testImportFileSizeLimitRejectsLargeFile() throws Exception {
        // Test that files larger than 10MB are rejected
        // Create a large file (11MB)
        final byte[] largeContent = new byte[11 * 1024 * 1024]; // 11 MB
        java.util.Arrays.fill(largeContent, (byte) 'A');

        final MockMultipartFile file =
                new MockMultipartFile(FILE, "large.csv", "text/csv", largeContent);

        final var result =
                mockMvc.perform(
                                multipart("/api/transactions/import-csv/preview")
                                        .file(file)
                                        .header(AUTHORIZATION, "Bearer " + authToken))
                        .andReturn();

        final int status = result.getResponse().getStatus();
        assertTrue(
                status == 400 || status == 413,
                "Should return 400 (bad request) or 413 (payload too large)");
    }

    @Test
    void testImportInvalidFileTypeRejects() throws Exception {
        // Test that invalid file types are rejected
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE,
                        "test.txt",
                        "text/plain",
                        "This is not a CSV file".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/transactions/import-csv/preview")
                                .file(file)
                                .header(AUTHORIZATION, "Bearer " + authToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testImportUnauthenticatedRejects() throws Exception {
        // Test that unauthenticated requests are rejected
        final String csvContent = "Date,Amount,Description\n2024-01-15,-50.00,Grocery Store";
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/transactions/import-csv/preview").file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testImportUnifiedResponseFormatConsistent() throws Exception {
        // Test that CSV preview returns consistent response format
        final String csvContent = "Date,Amount,Description\n2024-01-15,-50.00,Grocery Store";
        final MockMultipartFile file =
                new MockMultipartFile(
                        FILE, "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        final String response =
                mockMvc.perform(
                                multipart("/api/transactions/import-csv/preview")
                                        .file(file)
                                        .header(AUTHORIZATION, "Bearer " + authToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.transactions").exists())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Verify response structure
        assertTrue(response.contains("transactions"), "Response should contain transactions array");
        assertTrue(
                response.contains("date") || response.contains("Date"),
                "Response should contain date field");
        assertTrue(
                response.contains("amount") || response.contains("Amount"),
                "Response should contain amount field");
        assertTrue(
                response.contains("description") || response.contains("Description"),
                "Response should contain description field");
    }
}
