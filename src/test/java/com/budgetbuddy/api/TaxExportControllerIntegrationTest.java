package com.budgetbuddy.api;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.TaxExportService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Tests for TaxExportController
 * Tests REST API endpoints with MockMvc to verify HTTP layer behavior
 * including content negotiation and Accept header handling
 */
@SpringBootTest(
    classes = com.budgetbuddy.BudgetBuddyApplication.class,
    webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("Tax Export Controller Integration Tests")
class TaxExportControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    // Note: @MockBean is deprecated in Spring Boot 3.4.0, but still functional
    @SuppressWarnings("deprecation")
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.budgetbuddy.security.rate.RateLimitService rateLimitService;

    @SuppressWarnings("deprecation")
    @org.springframework.boot.test.mock.mockito.MockBean
    private com.budgetbuddy.security.ddos.DDoSProtectionService ddosProtectionService;

    private UserTable testUser;
    private String accessToken;

    @BeforeEach
    void setUp() throws Exception {
        // Mock rate limiting services to allow all requests in tests
        when(rateLimitService.isAllowed(anyString(), anyString())).thenReturn(true);
        when(ddosProtectionService.isAllowed(anyString())).thenReturn(true);
        
        // Clear security context to ensure clean state for each test
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        
        // Create a test user
        String email = "tax-export-test-" + UUID.randomUUID() + "@example.com";
        String base64PasswordHash = java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes());
        testUser = userService.createUserSecure(
                email,
                base64PasswordHash,
                "Test",
                "User"
        );
        
        // Authenticate to get access token
        AuthRequest authRequest = new AuthRequest(email, base64PasswordHash);
        AuthResponse authResponse = authService.authenticate(authRequest);
        accessToken = authResponse.getAccessToken();
        
        // Create some test transactions for tax export
        createTestTransactions();
    }
    
    private void createTestTransactions() {
        // Create a salary transaction
        TransactionTable salaryTx = new TransactionTable();
        salaryTx.setTransactionId(UUID.randomUUID().toString());
        salaryTx.setUserId(testUser.getUserId());
        salaryTx.setAmount(new BigDecimal("5000.00"));
        salaryTx.setTransactionDate(LocalDate.of(2025, 1, 15).toString());
        salaryTx.setDescription("Salary Payment");
        salaryTx.setCategoryPrimary("SALARY");
        transactionRepository.save(salaryTx);
        
        // Create an interest transaction
        TransactionTable interestTx = new TransactionTable();
        interestTx.setTransactionId(UUID.randomUUID().toString());
        interestTx.setUserId(testUser.getUserId());
        interestTx.setAmount(new BigDecimal("250.00"));
        interestTx.setTransactionDate(LocalDate.of(2025, 6, 30).toString());
        interestTx.setDescription("Interest Income");
        interestTx.setCategoryPrimary("INTEREST");
        transactionRepository.save(interestTx);
    }

    @Test
    @DisplayName("Should return tax summary with Accept: application/json header")
    void testGetTaxSummary_WithJsonAcceptHeader_ShouldReturnJson() throws Exception {
        // When/Then - Make actual HTTP request with Accept header
        mockMvc.perform(get("/api/tax/summary")
                        .param("year", "2025")
                        .header("Authorization", "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalSalary").exists())
                .andExpect(jsonPath("$.totalInterest").exists());
    }

    @Test
    @DisplayName("Should return tax summary without explicit Accept header (defaults to JSON)")
    void testGetTaxSummary_WithoutAcceptHeader_ShouldReturnJson() throws Exception {
        // When/Then - Make HTTP request without Accept header (should default to JSON)
        mockMvc.perform(get("/api/tax/summary")
                        .param("year", "2025")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalSalary").exists());
    }

    @Test
    @DisplayName("Should return 406 when requesting unsupported content type")
    void testGetTaxSummary_WithUnsupportedAcceptHeader_ShouldReturn406() throws Exception {
        // When/Then - Request XML which is not supported
        mockMvc.perform(get("/api/tax/summary")
                        .param("year", "2025")
                        .header("Authorization", "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable()); // 406 Not Acceptable
    }

    @Test
    @DisplayName("Should export CSV with Accept: text/csv header")
    void testExportTaxDataCSV_WithCsvAcceptHeader_ShouldReturnCsv() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/tax/export/csv")
                        .param("year", "2025")
                        .header("Authorization", "Bearer " + accessToken)
                        .accept("text/csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"))
                .andExpect(header().string("Content-Disposition", 
                    org.hamcrest.Matchers.containsString("tax_export_2025.csv")));
    }

    @Test
    @DisplayName("Should export CSV even when Accept: application/json is sent (iOS app compatibility)")
    void testExportTaxDataCSV_WithJsonAcceptHeader_ShouldStillReturnCsv() throws Exception {
        // When/Then - iOS app sends Accept: application/json for all requests
        // But CSV export should still work and return CSV
        mockMvc.perform(get("/api/tax/export/csv")
                        .param("year", "2025")
                        .header("Authorization", "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv")) // Still returns CSV despite Accept: application/json
                .andExpect(header().string("Content-Disposition", 
                    org.hamcrest.Matchers.containsString("tax_export_2025.csv")));
    }

    @Test
    @DisplayName("Should export JSON with Accept: application/json header")
    void testExportTaxDataJSON_WithJsonAcceptHeader_ShouldReturnJson() throws Exception {
        // When/Then
        mockMvc.perform(get("/api/tax/export/json")
                        .param("year", "2025")
                        .header("Authorization", "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
}

