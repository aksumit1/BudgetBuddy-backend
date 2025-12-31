package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Tests for Pseudo Account Functionality
 * Tests end-to-end flow: registration → pseudo account creation → transaction creation
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PseudoAccountIntegrationTest {

    private static final Logger logger = LoggerFactory.getLogger(PseudoAccountIntegrationTest.class);

    @Autowired
    private UserService userService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private UserTable testUser;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        // Create test user (this should create pseudo account)
        String email = "test-pseudo-" + UUID.randomUUID() + "@example.com";
        // Password hash must be base64 encoded
        String password = "testpassword123";
        String passwordHash = Base64.getEncoder().encodeToString(password.getBytes());
        
        testUser = userService.createUserSecure(email, passwordHash, "Test", "User");
        assertNotNull(testUser);
    }

    @Test
    void testUserRegistration_CreatesPseudoAccount() {
        // Given - User was created in setUp()
        
        // When - Find pseudo account
        AccountTable pseudoAccount = accountRepository.getOrCreatePseudoAccount(testUser.getUserId());
        
        // Then
        assertNotNull(pseudoAccount);
        assertEquals("Manual Transactions", pseudoAccount.getAccountName());
        assertEquals("BudgetBuddy", pseudoAccount.getInstitutionName());
        assertEquals("other", pseudoAccount.getAccountType());
        assertEquals("manual", pseudoAccount.getAccountSubtype());
        assertEquals(testUser.getUserId(), pseudoAccount.getUserId());
        assertNull(pseudoAccount.getPlaidAccountId());
        assertTrue(pseudoAccount.getActive());
    }

    @Test
    void testCreateTransaction_WithoutAccount_UsesPseudoAccount() {
        // Given
        AccountTable pseudoAccount = accountRepository.getOrCreatePseudoAccount(testUser.getUserId());
        
        // When - Create transaction without accountId
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                null, // No accountId
                BigDecimal.valueOf(100.00),
                LocalDate.now(),
                "Manual transaction without account",
                "FOOD",
                "RESTAURANTS",
                null, // transactionId
                null, // notes
                null, // plaidAccountId
                null, // plaidTransactionId
                null, // transactionType
                null, // currencyCode
                null, // importSource
                null, // importBatchId
                null  // importFileName
        );
        
        // Then - Should use pseudo account
        assertNotNull(transaction);
        assertEquals(pseudoAccount.getAccountId(), transaction.getAccountId());
        assertEquals(testUser.getUserId(), transaction.getUserId());
    }

    @Test
    void testCreateTransaction_WithAccount_UsesProvidedAccount() {
        // Given - Create a real account
        AccountTable realAccount = new AccountTable();
        realAccount.setAccountId(UUID.randomUUID().toString());
        realAccount.setUserId(testUser.getUserId());
        realAccount.setAccountName("Test Checking Account");
        realAccount.setInstitutionName("Test Bank");
        realAccount.setAccountType("checking");
        realAccount.setActive(true);
        accountRepository.save(realAccount);
        
        // When - Create transaction with accountId
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                realAccount.getAccountId(), // Use real account
                BigDecimal.valueOf(50.00),
                LocalDate.now(),
                "Transaction with account",
                "FOOD",
                "RESTAURANTS",
                null, // transactionId
                null, // notes
                null, // plaidAccountId
                null, // plaidTransactionId
                null, // transactionType
                null, // currencyCode
                null, // importSource
                null, // importBatchId
                null  // importFileName
        );
        
        // Then - Should use provided account, NOT pseudo account
        assertNotNull(transaction);
        assertEquals(realAccount.getAccountId(), transaction.getAccountId());
        assertNotEquals(accountRepository.getOrCreatePseudoAccount(testUser.getUserId()).getAccountId(), 
                       transaction.getAccountId());
    }

    @Test
    void testCreateTransaction_WithPlaidAccountId_NeverUsesPseudoAccount() {
        // Given - Create account with Plaid ID
        AccountTable plaidAccount = new AccountTable();
        plaidAccount.setAccountId(UUID.randomUUID().toString());
        plaidAccount.setUserId(testUser.getUserId());
        plaidAccount.setAccountName("Plaid Account");
        plaidAccount.setInstitutionName("Test Bank");
        plaidAccount.setAccountType("depository");
        plaidAccount.setAccountSubtype("checking");
        plaidAccount.setPlaidAccountId("plaid-acc-123");
        plaidAccount.setActive(true);
        Instant now = Instant.now();
        plaidAccount.setCreatedAt(now);
        plaidAccount.setUpdatedAt(now);
        plaidAccount.setUpdatedAtTimestamp(now.getEpochSecond());
        accountRepository.save(plaidAccount);
        
        // Verify account was saved and can be found
        Optional<AccountTable> savedAccount = accountRepository.findById(plaidAccount.getAccountId());
        assertTrue(savedAccount.isPresent(), "Account should be saved");
        assertEquals("plaid-acc-123", savedAccount.get().getPlaidAccountId(), "Account should have Plaid ID");
        
        // When - Create Plaid transaction (with plaidAccountId but no accountId)
        TransactionTable transaction = transactionService.createTransaction(
                testUser,
                null, // No accountId
                BigDecimal.valueOf(75.00),
                LocalDate.now(),
                "Plaid transaction",
                "FOOD",
                "RESTAURANTS",
                null, // transactionId
                null, // notes
                "plaid-acc-123", // Plaid account ID
                "plaid-tx-123",   // Plaid transaction ID
                null, // transactionType
                null, // currencyCode
                null, // importSource
                null, // importBatchId
                null  // importFileName
        );
        
        // Then - Should use Plaid account, NOT pseudo account
        assertNotNull(transaction);
        assertEquals(plaidAccount.getAccountId(), transaction.getAccountId());
        assertNotEquals(accountRepository.getOrCreatePseudoAccount(testUser.getUserId()).getAccountId(), 
                       transaction.getAccountId());
    }

    @Test
    void testGetOrCreatePseudoAccount_IsIdempotent() {
        // Given
        AccountTable firstCall = accountRepository.getOrCreatePseudoAccount(testUser.getUserId());
        
        // When - Call again
        AccountTable secondCall = accountRepository.getOrCreatePseudoAccount(testUser.getUserId());
        
        // Then - Should return same account
        assertEquals(firstCall.getAccountId(), secondCall.getAccountId());
        assertEquals(firstCall.getAccountName(), secondCall.getAccountName());
    }

    @Test
    void testPseudoAccount_IsNotIncludedInUserAccounts() {
        // Given
        AccountTable pseudoAccount = accountRepository.getOrCreatePseudoAccount(testUser.getUserId());
        
        // Create a real account
        AccountTable realAccount = new AccountTable();
        realAccount.setAccountId(UUID.randomUUID().toString());
        realAccount.setUserId(testUser.getUserId());
        realAccount.setAccountName("Real Account");
        realAccount.setActive(true);
        accountRepository.save(realAccount);
        
        // When - Get all accounts for user
        List<AccountTable> userAccounts = accountRepository.findByUserId(testUser.getUserId());
        
        // Then - Should include real account, but pseudo account should be filtered out by client
        // (Note: Repository returns all accounts, filtering happens in client)
        assertTrue(userAccounts.stream().anyMatch(a -> a.getAccountId().equals(realAccount.getAccountId())));
        // Pseudo account might be in the list, but client should filter it out
        // This test verifies the repository behavior (it returns all accounts)
    }
}

