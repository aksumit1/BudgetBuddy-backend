package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.AuthRequest;
import com.budgetbuddy.dto.AuthResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.service.AuthService;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for API Compatibility
 * Tests that backend responses match expected iOS app formats
 * 
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ApiCompatibilityIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserService userService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BudgetService budgetService;

    @Autowired
    private GoalService goalService;

    @Autowired
    private ObjectMapper objectMapper;

    private UserTable testUser;
    private String testEmail;
    private String testPasswordHash;
    private String testClientSalt;

    @BeforeEach
    void setUp() {
        testEmail = "compat-test-" + UUID.randomUUID() + "@example.com";
        testClientSalt = UUID.randomUUID().toString();
        testPasswordHash = "test-hash-" + UUID.randomUUID().toString();

        // Create test user
        testUser = userService.createUserSecure(
                testEmail,
                testPasswordHash,
                testClientSalt,
                "Test",
                "User"
        );

        // Authenticate to verify user is valid
        AuthRequest authRequest = new AuthRequest(testEmail, testPasswordHash, testClientSalt);
        AuthResponse authResponse = authService.authenticate(authRequest);
        assertNotNull(authResponse.getAccessToken(), "Authentication should succeed");
    }

    @Test
    void testAuthResponse_Format_MatchesIOS() throws Exception {
        // Given
        AuthRequest authRequest = new AuthRequest(testEmail, testPasswordHash, testClientSalt);

        // When
        AuthResponse response = authService.authenticate(authRequest);

        // Then - Verify response structure matches iOS expectations
        assertNotNull(response);
        assertNotNull(response.getAccessToken(), "accessToken must be present");
        assertNotNull(response.getRefreshToken(), "refreshToken must be present");
        assertNotNull(response.getExpiresAt(), "expiresAt must be present");
        assertNotNull(response.getUser(), "user must be present");
        assertNotNull(response.getUser().getId(), "user.id must be present");
        assertNotNull(response.getUser().getEmail(), "user.email must be present");

        // Serialize to JSON and verify field names
        String json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains("\"accessToken\""), "JSON must contain 'accessToken' field");
        assertTrue(json.contains("\"refreshToken\""), "JSON must contain 'refreshToken' field");
        assertTrue(json.contains("\"expiresAt\""), "JSON must contain 'expiresAt' field");
        assertTrue(json.contains("\"user\""), "JSON must contain 'user' field");
    }

    @Test
    void testTransactionResponse_Format_MatchesIOS() throws Exception {
        // Given - Create a transaction
        AccountTable account = createTestAccount();
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                account.getAccountId(),
                new BigDecimal("100.50"),
                LocalDate.now(),
                "Test Transaction",
                "groceries"
        );

        // When - Serialize to JSON
        String json = objectMapper.writeValueAsString(transaction);

        // Then - Verify field names and types
        assertTrue(json.contains("\"transactionId\""), "JSON must contain 'transactionId' field");
        assertTrue(json.contains("\"accountId\""), "JSON must contain 'accountId' field");
        assertTrue(json.contains("\"amount\""), "JSON must contain 'amount' field");
        assertTrue(json.contains("\"transactionDate\""), "JSON must contain 'transactionDate' field");
        assertTrue(json.contains("\"category\""), "JSON must contain 'category' field");
        assertTrue(json.contains("\"description\""), "JSON must contain 'description' field");

        // Verify date format is "YYYY-MM-DD"
        assertTrue(transaction.getTransactionDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                "transactionDate must be in YYYY-MM-DD format");
    }

    @Test
    void testAccountResponse_Format_MatchesIOS() throws Exception {
        // Given - Create an account
        AccountTable account = createTestAccount();

        // When - Serialize to JSON
        String json = objectMapper.writeValueAsString(account);

        // Then - Verify field names and types
        assertTrue(json.contains("\"accountId\""), "JSON must contain 'accountId' field");
        assertTrue(json.contains("\"accountName\""), "JSON must contain 'accountName' field");
        assertTrue(json.contains("\"institutionName\""), "JSON must contain 'institutionName' field");
        assertTrue(json.contains("\"accountType\""), "JSON must contain 'accountType' field");
        assertTrue(json.contains("\"balance\""), "JSON must contain 'balance' field");
        assertTrue(json.contains("\"currencyCode\""), "JSON must contain 'currencyCode' field");
    }

    @Test
    void testBudgetResponse_Format_MatchesIOS() throws Exception {
        // Given - Create a budget
        BudgetTable budget = budgetService.createOrUpdateBudget(
                testUser,
                "groceries",
                new BigDecimal("500.00")
        );

        // When - Serialize to JSON
        String json = objectMapper.writeValueAsString(budget);

        // Then - Verify field names and types
        assertTrue(json.contains("\"budgetId\""), "JSON must contain 'budgetId' field");
        assertTrue(json.contains("\"category\""), "JSON must contain 'category' field");
        assertTrue(json.contains("\"monthlyLimit\""), "JSON must contain 'monthlyLimit' field");
        assertTrue(json.contains("\"currentSpent\""), "JSON must contain 'currentSpent' field");
    }

    @Test
    void testGoalResponse_Format_MatchesIOS() throws Exception {
        // Given - Create a goal
        GoalTable goal = goalService.createGoal(
                testUser,
                "Test Goal",
                "Test Description",
                new BigDecimal("10000.00"),
                LocalDate.now().plusYears(1),
                "savings"
        );

        // When - Serialize to JSON
        String json = objectMapper.writeValueAsString(goal);

        // Then - Verify field names and types
        assertTrue(json.contains("\"goalId\""), "JSON must contain 'goalId' field");
        assertTrue(json.contains("\"name\""), "JSON must contain 'name' field");
        assertTrue(json.contains("\"targetAmount\""), "JSON must contain 'targetAmount' field");
        assertTrue(json.contains("\"currentAmount\""), "JSON must contain 'currentAmount' field");
        assertTrue(json.contains("\"targetDate\""), "JSON must contain 'targetDate' field");
        assertTrue(json.contains("\"monthlyContribution\""), "JSON must contain 'monthlyContribution' field");
    }

    @Test
    void testTransactionListResponse_Format_MatchesIOS() throws Exception {
        // Given - Create multiple transactions
        AccountTable account = createTestAccount();
        transactionService.createTransaction(testUser, account.getAccountId(),
                new BigDecimal("50.00"), LocalDate.now(), "Transaction 1", "groceries");
        transactionService.createTransaction(testUser, account.getAccountId(),
                new BigDecimal("75.00"), LocalDate.now(), "Transaction 2", "dining");

        // When - Get transactions
        List<TransactionTable> transactions = transactionService.getTransactions(testUser, 0, 10);

        // Then - Verify list structure
        assertNotNull(transactions);
        assertFalse(transactions.isEmpty());

        // Serialize list to JSON
        String json = objectMapper.writeValueAsString(transactions);
        assertTrue(json.startsWith("["), "Transactions list must be a JSON array");
        assertTrue(json.contains("\"transactionId\""), "Each transaction must have 'transactionId'");
    }

    @Test
    void testAccountListResponse_Format_MatchesIOS() throws Exception {
        // Given - Create multiple accounts
        createTestAccount();
        createTestAccount();

        // When - Get accounts
        List<AccountTable> accounts = accountRepository.findByUserId(testUser.getUserId());

        // Then - Verify list structure
        assertNotNull(accounts);
        assertFalse(accounts.isEmpty());

        // Serialize list to JSON
        String json = objectMapper.writeValueAsString(accounts);
        assertTrue(json.startsWith("["), "Accounts list must be a JSON array");
        assertTrue(json.contains("\"accountId\""), "Each account must have 'accountId'");
    }

    @Test
    void testDateFormats_AreConsistent() throws Exception {
        // Given - Create entities with dates
        AccountTable account = createTestAccount();
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                account.getAccountId(),
                new BigDecimal("100.00"),
                LocalDate.now(),
                "Test",
                "groceries"
        );

        // When - Serialize to JSON
        String accountJson = objectMapper.writeValueAsString(account);

        // Then - Verify date formats
        // Transaction date should be "YYYY-MM-DD"
        assertTrue(transaction.getTransactionDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                "Transaction date must be YYYY-MM-DD format");

        // Instant dates should be epoch seconds (Long) or ISO8601
        if (account.getCreatedAt() != null) {
            assertTrue(accountJson.contains("\"createdAt\""), "Account must have createdAt field");
        }
    }

    @Test
    void testBigDecimal_SerializesAsNumber() throws Exception {
        // Given - Create entities with BigDecimal amounts
        AccountTable account = createTestAccount();
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                account.getAccountId(),
                new BigDecimal("123.45"),
                LocalDate.now(),
                "Test",
                "groceries"
        );

        // When - Serialize to JSON
        String transactionJson = objectMapper.writeValueAsString(transaction);
        String accountJson = objectMapper.writeValueAsString(account);

        // Then - Verify BigDecimal is serialized as number (not string)
        // JSON numbers don't have quotes, strings do
        assertTrue(transactionJson.matches(".*\"amount\"\\s*:\\s*\\d+\\.?\\d*.*"),
                "Amount must be serialized as a number, not a string");
        assertTrue(accountJson.matches(".*\"balance\"\\s*:\\s*\\d+\\.?\\d*.*"),
                "Balance must be serialized as a number, not a string");
    }

    // Helper method to create test account
    private AccountTable createTestAccount() {
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUser.getUserId());
        account.setAccountName("Test Account");
        account.setInstitutionName("Test Bank");
        account.setAccountType("checking");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        accountRepository.save(account);
        return account;
    }
}

