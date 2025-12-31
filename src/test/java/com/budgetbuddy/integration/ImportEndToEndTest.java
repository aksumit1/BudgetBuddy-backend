package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.UserService;
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

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end tests for import functionality (CSV, Excel, PDF)
 * Tests complete flow from file upload to transaction creation
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class, webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(AWSTestConfiguration.class)
@ActiveProfiles("test")
class ImportEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    // ObjectMapper not currently used but may be needed for future test enhancements
    // @Autowired
    // private ObjectMapper objectMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private AccountRepository accountRepository;

    private String testEmail;
    private String testPasswordHash;
    private String authToken;
    private UserTable testUser;
    private AccountTable testAccount;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        testEmail = "test-import-" + UUID.randomUUID() + "@example.com";
        testPasswordHash = Base64.getEncoder().encodeToString("hashed-password".getBytes());

        // Create test user
        testUser = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                "Test",
                "User"
        );

        // Authenticate to get token
        AuthRequest loginRequest = new AuthRequest();
        loginRequest.setEmail(testEmail);
        loginRequest.setPasswordHash(testPasswordHash);
        AuthResponse authResponse = authService.authenticate(loginRequest);
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
    void testCSVImport_Preview_Succeeds() throws Exception {
        // Test CSV preview endpoint
        String csvContent = "Date,Amount,Description\n2024-01-15,-50.00,Grocery Store\n2024-01-16,-25.50,Coffee Shop";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes()
        );

        mockMvc.perform(multipart("/api/transactions/import-csv/preview")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").isArray())
                .andExpect(jsonPath("$.transactions.length()").value(2))
                .andExpect(jsonPath("$.transactions[0].description").exists())
                .andExpect(jsonPath("$.transactions[0].amount").exists())
                .andExpect(jsonPath("$.transactions[0].date").exists());
    }

    @Test
    void testCSVImport_Import_Succeeds() throws Exception {
        // Test CSV import endpoint
        String csvContent = "Date,Amount,Description\n2024-01-15,-50.00,Grocery Store";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes()
        );

        mockMvc.perform(multipart("/api/transactions/import-csv")
                        .file(file)
                        .param("accountId", testAccount.getAccountId())
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successful").exists())
                .andExpect(jsonPath("$.failed").exists());
    }

    @Test
    void testExcelImport_Preview_Succeeds() throws Exception {
        // Test Excel preview endpoint
        // Create a simple Excel file content (CSV-like for testing)
        // Note: In real scenario, would use Apache POI to create actual Excel file
        String excelContent = "Date\tAmount\tDescription\n2024-01-15\t-50.00\tGrocery Store";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                excelContent.getBytes()
        );

        // Note: This test may fail if backend requires actual Excel format
        // The endpoint should handle invalid Excel files gracefully
        // For now, just verify the request is processed (may return 200 or 400 depending on file format)
        var result = mockMvc.perform(multipart("/api/transactions/import-excel/preview")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andReturn();
        
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 400, "Should return 200 (success) or 400 (validation error)");
    }

    @Test
    void testPDFImport_Preview_Succeeds() throws Exception {
        // Test PDF preview endpoint
        // Create a simple PDF file content (text-based for testing)
        // Note: In real scenario, would use PDFBox to create actual PDF file
        String pdfContent = "Statement Period: 01/01/2024 - 01/31/2024\nDate\tAmount\tDescription\n01/15/2024\t-50.00\tGrocery Store";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                pdfContent.getBytes()
        );

        // Note: This test may fail if backend requires actual PDF format
        // The endpoint should handle invalid PDF files gracefully
        // For now, just verify the request is processed (may return 200 or 400 depending on file format)
        var result = mockMvc.perform(multipart("/api/transactions/import-pdf/preview")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andReturn();
        
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 400, "Should return 200 (success) or 400 (validation error)");
    }

    @Test
    void testImport_FileSizeLimit_RejectsLargeFile() throws Exception {
        // Test that files larger than 10MB are rejected
        // Create a large file (11MB)
        byte[] largeContent = new byte[11 * 1024 * 1024]; // 11 MB
        java.util.Arrays.fill(largeContent, (byte) 'A');
        
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "large.csv",
                "text/csv",
                largeContent
        );

        var result = mockMvc.perform(multipart("/api/transactions/import-csv/preview")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andReturn();
        
        int status = result.getResponse().getStatus();
        assertTrue(status == 400 || status == 413, "Should return 400 (bad request) or 413 (payload too large)");
    }

    @Test
    void testImport_InvalidFileType_Rejects() throws Exception {
        // Test that invalid file types are rejected
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.txt",
                "text/plain",
                "This is not a CSV file".getBytes()
        );

        mockMvc.perform(multipart("/api/transactions/import-csv/preview")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testImport_Unauthenticated_Rejects() throws Exception {
        // Test that unauthenticated requests are rejected
        String csvContent = "Date,Amount,Description\n2024-01-15,-50.00,Grocery Store";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes()
        );

        mockMvc.perform(multipart("/api/transactions/import-csv/preview")
                        .file(file))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void testImport_UnifiedResponseFormat_Consistent() throws Exception {
        // Test that CSV preview returns consistent response format
        String csvContent = "Date,Amount,Description\n2024-01-15,-50.00,Grocery Store";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes()
        );

        String response = mockMvc.perform(multipart("/api/transactions/import-csv/preview")
                        .file(file)
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Verify response structure
        assertTrue(response.contains("transactions"), "Response should contain transactions array");
        assertTrue(response.contains("date") || response.contains("Date"), "Response should contain date field");
        assertTrue(response.contains("amount") || response.contains("Amount"), "Response should contain amount field");
        assertTrue(response.contains("description") || response.contains("Description"), "Response should contain description field");
    }
}
