package com.budgetbuddy.integration;



import java.nio.charset.StandardCharsets;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration Tests for API Compatibility Tests that backend responses match expected iOS app
 * formats
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
class ApiCompatibilityIntegrationTest {

    @Autowired private AuthService authService;

    @Autowired private UserService userService;

    @Autowired private TransactionService transactionService;

    @Autowired private AccountRepository accountRepository;

    @Autowired private BudgetService budgetService;

    @Autowired private GoalService goalService;

    @Autowired private ObjectMapper objectMapper;

    private UserTable testUser;
    private String testEmail;
    private String testPasswordHash;
    private String testClientSalt;

    @BeforeEach
    void setUp() {
        testEmail = "compat-test-" + UUID.randomUUID() + "@example.com";
        // Use proper base64-encoded strings
        // BREAKING CHANGE: Client salt removed
        testPasswordHash =
                java.util.Base64.getEncoder().encodeToString("hashed-password".getBytes(StandardCharsets.UTF_8));

        // Create test user
        testUser = userService.createUserSecure(testEmail, testPasswordHash, "Test", "User");

        // Authenticate to verify user is valid
        final AuthRequest authRequest = new AuthRequest(testEmail, testPasswordHash);
        final AuthResponse authResponse = authService.authenticate(authRequest);
        assertNotNull(authResponse.getAccessToken(), "Authentication should succeed");
    }

    @Test
    void testAuthResponseFormatMatchesIOS() throws Exception {
        // Given
        final AuthRequest authRequest = new AuthRequest(testEmail, testPasswordHash);

        // When
        final AuthResponse response = authService.authenticate(authRequest);

        // Then - Verify response structure matches iOS expectations
        assertNotNull(response);
        assertNotNull(response.getAccessToken(), "accessToken must be present");
        assertNotNull(response.getRefreshToken(), "refreshToken must be present");
        assertNotNull(response.getExpiresAt(), "expiresAt must be present");
        assertNotNull(response.getUser(), "user must be present");
        assertNotNull(response.getUser().getId(), "user.id must be present");
        assertNotNull(response.getUser().getEmail(), "user.email must be present");

        // Serialize to JSON and verify field names
        final String json = objectMapper.writeValueAsString(response);
        assertTrue(json.contains("\"accessToken\""), "JSON must contain 'accessToken' field");
        assertTrue(json.contains("\"refreshToken\""), "JSON must contain 'refreshToken' field");
        assertTrue(json.contains("\"expiresAt\""), "JSON must contain 'expiresAt' field");
        assertTrue(json.contains("\"user\""), "JSON must contain 'user' field");
    }

    @Test
    void testTransactionResponseFormatMatchesIOS() throws Exception {
        // Given - Create a transaction
        final AccountTable account = createTestAccount();
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        account.getAccountId(),
                        new BigDecimal("100.50"),
                        LocalDate.now(),
                        "Test Transaction",
                        "groceries");

        // When - Serialize to JSON
        final String json = objectMapper.writeValueAsString(transaction);

        // Then - Verify field names and types
        assertTrue(json.contains("\"transactionId\""), "JSON must contain 'transactionId' field");
        assertTrue(json.contains("\"accountId\""), "JSON must contain 'accountId' field");
        assertTrue(json.contains("\"amount\""), "JSON must contain 'amount' field");
        assertTrue(
                json.contains("\"transactionDate\""), "JSON must contain 'transactionDate' field");
        // Note: TransactionTable uses categoryPrimary and categoryDetailed, not a single "category"
        // field
        assertTrue(
                json.contains("\"categoryPrimary\""), "JSON must contain 'categoryPrimary' field");
        assertTrue(json.contains("\"description\""), "JSON must contain 'description' field");

        // Verify date format is "YYYY-MM-DD"
        assertTrue(
                transaction.getTransactionDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                "transactionDate must be in YYYY-MM-DD format");
    }

    @Test
    void testAccountResponseFormatMatchesIOS() throws Exception {
        // Given - Create an account
        final AccountTable account = createTestAccount();

        // When - Serialize to JSON
        final String json = objectMapper.writeValueAsString(account);

        // Then - Verify field names and types
        assertTrue(json.contains("\"accountId\""), "JSON must contain 'accountId' field");
        assertTrue(json.contains("\"accountName\""), "JSON must contain 'accountName' field");
        assertTrue(
                json.contains("\"institutionName\""), "JSON must contain 'institutionName' field");
        assertTrue(json.contains("\"accountType\""), "JSON must contain 'accountType' field");
        assertTrue(json.contains("\"balance\""), "JSON must contain 'balance' field");
        assertTrue(json.contains("\"currencyCode\""), "JSON must contain 'currencyCode' field");
    }

    @Test
    void testBudgetResponseFormatMatchesIOS() throws Exception {
        // Given - Create a budget
        final BudgetTable budget =
                budgetService.createOrUpdateBudget(testUser, "groceries", new BigDecimal("500.00"));

        // When - Serialize to JSON
        final String json = objectMapper.writeValueAsString(budget);

        // Then - Verify field names and types
        assertTrue(json.contains("\"budgetId\""), "JSON must contain 'budgetId' field");
        assertTrue(json.contains("\"category\""), "JSON must contain 'category' field");
        assertTrue(json.contains("\"monthlyLimit\""), "JSON must contain 'monthlyLimit' field");
        assertTrue(json.contains("\"currentSpent\""), "JSON must contain 'currentSpent' field");
    }

    @Test
    void testGoalResponseFormatMatchesIOS() throws Exception {
        // Given - Create a goal
        final GoalTable goal =
                goalService.createGoal(
                        testUser,
                        "Test Goal",
                        "Test Description",
                        new BigDecimal("10000.00"),
                        LocalDate.now().plusYears(1),
                        "savings");

        // When - Serialize to JSON
        final String json = objectMapper.writeValueAsString(goal);

        // Then - Verify field names and types
        assertTrue(json.contains("\"goalId\""), "JSON must contain 'goalId' field");
        assertTrue(json.contains("\"name\""), "JSON must contain 'name' field");
        assertTrue(json.contains("\"targetAmount\""), "JSON must contain 'targetAmount' field");
        assertTrue(json.contains("\"currentAmount\""), "JSON must contain 'currentAmount' field");
        assertTrue(json.contains("\"targetDate\""), "JSON must contain 'targetDate' field");
        assertTrue(
                json.contains("\"monthlyContribution\""),
                "JSON must contain 'monthlyContribution' field");
    }

    @Test
    void testGoalResponseAccountIdsAlwaysIncludedInJSON() throws Exception {
        // Test Case 1: Goal with null accountIds
        // Given - Create a goal without accountIds (defaults to null)
        final GoalTable goalWithNull =
                goalService.createGoal(
                        testUser,
                        "Goal Without Accounts",
                        "Test Description",
                        new BigDecimal("5000.00"),
                        LocalDate.now().plusYears(1),
                        "CUSTOM",
                        null, // goalId
                        null, // currentAmount
                        null // accountIds - null
                );

        // When - Serialize to JSON
        final String jsonWithNull = objectMapper.writeValueAsString(goalWithNull);

        // Then - accountIds field must be present (even if null)
        // With @JsonInclude(JsonInclude.Include.ALWAYS), null should be included as
        // "accountIds":null
        assertTrue(
                jsonWithNull.contains("\"accountIds\""),
                "JSON must contain 'accountIds' field even when null. JSON: " + jsonWithNull);
        // Verify it's either null or an empty array
        assertTrue(
                jsonWithNull.contains("\"accountIds\":null")
                        || jsonWithNull.contains("\"accountIds\":[]"),
                "accountIds should be null or empty array when not set. JSON: " + jsonWithNull);

        // Test Case 2: Goal with empty accountIds list
        // Given - Create a goal with empty accountIds
        final GoalTable goalWithEmpty =
                goalService.createGoal(
                        testUser,
                        "Goal With Empty Accounts",
                        "Test Description",
                        new BigDecimal("5000.00"),
                        LocalDate.now().plusYears(1),
                        "CUSTOM",
                        null, // goalId
                        null, // currentAmount
                        java.util.Collections.emptyList() // accountIds - empty list
                );

        // When - Serialize to JSON
        final String jsonWithEmpty = objectMapper.writeValueAsString(goalWithEmpty);

        // Then - accountIds field must be present as empty array
        assertTrue(
                jsonWithEmpty.contains("\"accountIds\""),
                "JSON must contain 'accountIds' field even when empty. JSON: " + jsonWithEmpty);
        assertTrue(
                jsonWithEmpty.contains("\"accountIds\":[]")
                        || jsonWithEmpty.contains("\"accountIds\" : []"),
                "accountIds should be empty array when set to empty list. JSON: " + jsonWithEmpty);

        // Test Case 3: Goal with accountIds (requires valid account)
        // Given - Create a test account first
        final AccountTable testAccount = createTestAccount();

        // Create goal with accountIds
        final GoalTable goalWithAccounts =
                goalService.createGoal(
                        testUser,
                        "Goal With Accounts",
                        "Test Description",
                        new BigDecimal("5000.00"),
                        LocalDate.now().plusYears(1),
                        "CUSTOM",
                        null, // goalId
                        null, // currentAmount
                        Arrays.asList(testAccount.getAccountId()) // accountIds - with account
                );

        // When - Serialize to JSON
        final String jsonWithAccounts = objectMapper.writeValueAsString(goalWithAccounts);

        // Then - accountIds field must be present with account IDs
        assertTrue(
                jsonWithAccounts.contains("\"accountIds\""),
                "JSON must contain 'accountIds' field when accounts are linked. JSON: "
                        + jsonWithAccounts);
        assertTrue(
                jsonWithAccounts.contains(testAccount.getAccountId().toLowerCase(Locale.ROOT)),
                "JSON must contain the account ID in accountIds array. JSON: " + jsonWithAccounts);
        // Verify it's an array format
        assertTrue(
                jsonWithAccounts.matches(
                        ".*\"accountIds\"\\s*:\\s*\\[.*"
                                + testAccount.getAccountId().toLowerCase(Locale.ROOT)
                                + ".*\\].*"),
                "accountIds should be a JSON array containing the account ID. JSON: "
                        + jsonWithAccounts);
    }

    @Test
    void testTransactionListResponseFormatMatchesIOS() throws Exception {
        // Given - Create multiple transactions
        final AccountTable account = createTestAccount();
        transactionService.createTransaction(
                testUser,
                account.getAccountId(),
                new BigDecimal("50.00"),
                LocalDate.now(),
                "Transaction 1",
                "groceries");
        transactionService.createTransaction(
                testUser,
                account.getAccountId(),
                new BigDecimal("75.00"),
                LocalDate.now(),
                "Transaction 2",
                "dining");

        // When - Get transactions
        final List<TransactionTable> transactions = transactionService.getTransactions(testUser, 0, 10);

        // Then - Verify list structure
        assertNotNull(transactions);
        assertFalse(transactions.isEmpty());

        // Serialize list to JSON
        final String json = objectMapper.writeValueAsString(transactions);
        assertTrue(json.startsWith("["), "Transactions list must be a JSON array");
        assertTrue(
                json.contains("\"transactionId\""), "Each transaction must have 'transactionId'");
    }

    @Test
    void testAccountListResponseFormatMatchesIOS() throws Exception {
        // Given - Create multiple accounts
        createTestAccount();
        createTestAccount();

        // When - Get accounts
        final List<AccountTable> accounts = accountRepository.findByUserId(testUser.getUserId());

        // Then - Verify list structure
        assertNotNull(accounts);
        assertFalse(accounts.isEmpty());

        // Serialize list to JSON
        final String json = objectMapper.writeValueAsString(accounts);
        assertTrue(json.startsWith("["), "Accounts list must be a JSON array");
        assertTrue(json.contains("\"accountId\""), "Each account must have 'accountId'");
    }

    @Test
    void testDateFormatsAreConsistent() throws Exception {
        // Given - Create entities with dates
        final AccountTable account = createTestAccount();
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        account.getAccountId(),
                        new BigDecimal("100.00"),
                        LocalDate.now(),
                        "Test",
                        "groceries");

        // When - Serialize to JSON
        final String accountJson = objectMapper.writeValueAsString(account);

        // Then - Verify date formats
        // Transaction date should be "YYYY-MM-DD"
        assertTrue(
                transaction.getTransactionDate().matches("\\d{4}-\\d{2}-\\d{2}"),
                "Transaction date must be YYYY-MM-DD format");

        // Instant dates should be epoch seconds (Long) or ISO8601
        if (account.getCreatedAt() != null) {
            assertTrue(accountJson.contains("\"createdAt\""), "Account must have createdAt field");
        }
    }

    @Test
    void testBigDecimalSerializesAsNumber() throws Exception {
        // Given - Create entities with BigDecimal amounts
        final AccountTable account = createTestAccount();
        final TransactionTable transaction =
                transactionService.createTransaction(
                        testUser,
                        account.getAccountId(),
                        new BigDecimal("123.45"),
                        LocalDate.now(),
                        "Test",
                        "groceries");

        // When - Serialize to JSON
        final String transactionJson = objectMapper.writeValueAsString(transaction);
        final String accountJson = objectMapper.writeValueAsString(account);

        // Then - Verify BigDecimal is serialized as number (not string)
        // JSON numbers don't have quotes, strings do
        assertTrue(
                transactionJson.matches(".*\"amount\"\\s*:\\s*\\d+\\.?\\d*.*"),
                "Amount must be serialized as a number, not a string");
        assertTrue(
                accountJson.matches(".*\"balance\"\\s*:\\s*\\d+\\.?\\d*.*"),
                "Balance must be serialized as a number, not a string");
    }

    // Helper method to create test account
    private AccountTable createTestAccount() {
        final AccountTable account = new AccountTable();
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
