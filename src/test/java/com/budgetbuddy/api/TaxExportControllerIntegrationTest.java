package com.budgetbuddy.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration Tests for TaxExportController Tests REST API endpoints with MockMvc to verify HTTP
 * layer behavior including content negotiation and Accept header handling
 */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SpringBootTest(
        classes = com.budgetbuddy.BudgetBuddyApplication.class,
        webEnvironment = org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@DisplayName("Tax Export Controller Integration Tests")
class TaxExportControllerIntegrationTest {

    private static final String YEAR = "year";
    private static final String AUTHORIZATION = "Authorization";

    @Autowired private MockMvc mockMvc;

    @Autowired private UserService userService;

    @Autowired private AuthService authService;

    @Autowired private TransactionService transactionService;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private UserRepository userRepository;

    @Autowired private ObjectMapper objectMapper;

    // Note: @MockitoBean is deprecated in Spring Boot 3.4.0, but still functional
    @SuppressWarnings("deprecation")
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.budgetbuddy.security.rate.RateLimitService rateLimitService;

    @SuppressWarnings("deprecation")
    @org.springframework.test.context.bean.override.mockito.MockitoBean
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
        final String email = "tax-export-test-" + UUID.randomUUID() + "@example.com";
        final String base64PasswordHash =
                java.util.Base64.getEncoder()
                        .encodeToString("hashed-password".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(email, base64PasswordHash, "Test", "User");

        // Authenticate to get access token
        final AuthRequest authRequest = new AuthRequest(email, base64PasswordHash);
        final AuthResponse authResponse = authService.authenticate(authRequest);
        accessToken = authResponse.getAccessToken();

        // Create some test transactions for tax export
        createTestTransactions();
    }

    private void createTestTransactions() {
        // Create a salary transaction
        final TransactionTable salaryTx = new TransactionTable();
        salaryTx.setTransactionId(UUID.randomUUID().toString());
        salaryTx.setUserId(testUser.getUserId());
        salaryTx.setAmount(new BigDecimal("5000.00"));
        salaryTx.setTransactionDate(LocalDate.of(2025, 1, 15).toString());
        salaryTx.setDescription("Salary Payment");
        salaryTx.setCategoryPrimary("SALARY");
        transactionRepository.save(salaryTx);

        // Create an interest transaction
        final TransactionTable interestTx = new TransactionTable();
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
    void testGetTaxSummaryWithJsonAcceptHeaderShouldReturnJson() throws Exception {
        // When/Then - Make actual HTTP request with Accept header
        mockMvc.perform(
                        get("/api/tax/summary")
                                .param(YEAR, "2025")
                                .header(AUTHORIZATION, "Bearer " + accessToken)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.totalSalary").exists())
                .andExpect(jsonPath("$.data.totalInterest").exists());
    }

    @Test
    @DisplayName("Should return tax summary without explicit Accept header (defaults to JSON)")
    void testGetTaxSummaryWithoutAcceptHeaderShouldReturnJson() throws Exception {
        // When/Then - Make HTTP request without Accept header (should default to JSON)
        mockMvc.perform(
                        get("/api/tax/summary")
                                .param(YEAR, "2025")
                                .header(AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.data.totalSalary").exists());
    }

    @Test
    @DisplayName("Should return 406 when requesting unsupported content type")
    void testGetTaxSummaryWithUnsupportedAcceptHeaderShouldReturn406() throws Exception {
        // When/Then - Request XML which is not supported
        mockMvc.perform(
                        get("/api/tax/summary")
                                .param(YEAR, "2025")
                                .header(AUTHORIZATION, "Bearer " + accessToken)
                                .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable()); // 406 Not Acceptable
    }

    @Test
    @DisplayName("Should export CSV with Accept: text/csv header")
    void testExportTaxDataCSVWithCsvAcceptHeaderShouldReturnCsv() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/tax/export/csv")
                                .param(YEAR, "2025")
                                .header(AUTHORIZATION, "Bearer " + accessToken)
                                .accept("text/csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"))
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.containsString(
                                                "tax_export_2025.csv")));
    }

    @Test
    @DisplayName(
            "Should export CSV even when Accept: application/json is sent (iOS app compatibility)")
    void testExportTaxDataCSVWithJsonAcceptHeaderShouldStillReturnCsv() throws Exception {
        // When/Then - iOS app sends Accept: application/json for all requests
        // But CSV export should still work and return CSV
        mockMvc.perform(
                        get("/api/tax/export/csv")
                                .param(YEAR, "2025")
                                .header(AUTHORIZATION, "Bearer " + accessToken)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv")) // Still returns CSV despite Accept:
                // application/json
                .andExpect(
                        header().string(
                                        "Content-Disposition",
                                        org.hamcrest.Matchers.containsString(
                                                "tax_export_2025.csv")));
    }

    @Test
    @DisplayName("Should export JSON with Accept: application/json header")
    void testExportTaxDataJSONWithJsonAcceptHeaderShouldReturnJson() throws Exception {
        // When/Then
        mockMvc.perform(
                        get("/api/tax/export/json")
                                .param(YEAR, "2025")
                                .header(AUTHORIZATION, "Bearer " + accessToken)
                                .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }
}
