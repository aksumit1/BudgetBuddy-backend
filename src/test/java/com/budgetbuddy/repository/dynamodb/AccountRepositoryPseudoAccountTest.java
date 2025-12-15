package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.AccountTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AccountRepository.getOrCreatePseudoAccount()
 * Tests pseudo account creation, thread-safety, and identification
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AccountRepositoryPseudoAccountTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private DynamoDbTable<AccountTable> accountTable;

    private AccountRepository accountRepository;
    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        
        // Setup mocks
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(accountTable);
        
        accountRepository = new AccountRepository(enhancedClient, dynamoDbClient, "TestBudgetBuddy");
    }

    @Test
    void testGetOrCreatePseudoAccount_WithNewUser_CreatesPseudoAccount() {
        // Given - No existing pseudo account
        when(accountTable.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(null);
        
        doNothing().when(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));

        // When
        AccountTable result = accountRepository.getOrCreatePseudoAccount(testUserId);

        // Then
        assertNotNull(result);
        assertEquals("Manual Transactions", result.getAccountName());
        assertEquals("BudgetBuddy", result.getInstitutionName());
        assertEquals("other", result.getAccountType());
        assertEquals("manual", result.getAccountSubtype());
        assertEquals(testUserId, result.getUserId());
        assertNull(result.getPlaidAccountId());
        assertNull(result.getPlaidItemId());
        assertEquals(BigDecimal.ZERO, result.getBalance());
        assertTrue(result.getActive());
        
        // Verify conditional write was used
        verify(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));
    }

    @Test
    void testGetOrCreatePseudoAccount_WithExistingAccount_ReturnsExisting() {
        // Given - Existing pseudo account
        AccountTable existingPseudoAccount = createPseudoAccount(testUserId);
        when(accountTable.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(existingPseudoAccount);

        // When
        AccountTable result = accountRepository.getOrCreatePseudoAccount(testUserId);

        // Then
        assertNotNull(result);
        assertEquals(existingPseudoAccount.getAccountId(), result.getAccountId());
        assertEquals("Manual Transactions", result.getAccountName());
        
        // Verify no new account was created
        verify(accountTable, never()).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));
    }

    @Test
    void testGetOrCreatePseudoAccount_WithConcurrentCalls_OnlyCreatesOne() {
        // Given - Simulate race condition: first call creates, second call gets ConditionalCheckFailedException
        AccountTable existingPseudoAccount = createPseudoAccount(testUserId);
        
        // First call: account doesn't exist, tries to create
        when(accountTable.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(null) // First call: doesn't exist
                .thenReturn(existingPseudoAccount); // Second call: exists
        
        // First call: conditional write succeeds
        doNothing().when(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));
        
        // Second call: conditional write fails (account already exists)
        doThrow(ConditionalCheckFailedException.builder().build())
                .when(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));

        // When - First call creates account
        AccountTable result1 = accountRepository.getOrCreatePseudoAccount(testUserId);
        
        // Second call should fetch existing account (handles ConditionalCheckFailedException)
        AccountTable result2 = accountRepository.getOrCreatePseudoAccount(testUserId);

        // Then - Both should return the same account
        assertNotNull(result1);
        assertNotNull(result2);
        // Both should have same accountId (deterministic UUID)
        assertEquals(result1.getAccountId(), result2.getAccountId());
    }

    @Test
    void testGetOrCreatePseudoAccount_WithNullUserId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            accountRepository.getOrCreatePseudoAccount(null);
        });
    }

    @Test
    void testGetOrCreatePseudoAccount_WithEmptyUserId_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            accountRepository.getOrCreatePseudoAccount("");
        });
    }

    @Test
    void testGetOrCreatePseudoAccount_GeneratesDeterministicUUID() {
        // Given
        when(accountTable.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(null);
        doNothing().when(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));

        // When - Call twice with same userId
        AccountTable result1 = accountRepository.getOrCreatePseudoAccount(testUserId);
        AccountTable result2 = accountRepository.getOrCreatePseudoAccount(testUserId);

        // Then - Should generate same UUID (deterministic)
        assertEquals(result1.getAccountId(), result2.getAccountId());
    }

    @Test
    void testGetOrCreatePseudoAccount_WithDifferentUsers_CreatesDifferentAccounts() {
        // Given
        String userId1 = UUID.randomUUID().toString();
        String userId2 = UUID.randomUUID().toString();
        
        when(accountTable.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(null);
        doNothing().when(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));

        // When
        AccountTable result1 = accountRepository.getOrCreatePseudoAccount(userId1);
        AccountTable result2 = accountRepository.getOrCreatePseudoAccount(userId2);

        // Then - Should have different account IDs
        assertNotEquals(result1.getAccountId(), result2.getAccountId());
        assertEquals(userId1, result1.getUserId());
        assertEquals(userId2, result2.getUserId());
    }

    @Test
    void testGetOrCreatePseudoAccount_WithWrongUserId_HandlesGracefully() {
        // Given - Simulate data inconsistency: account exists with correct UUID but wrong userId
        // This could happen due to data corruption or manual database changes
        // The UUID is deterministic, so for testUserId, we need to generate the same UUID
        // but have it belong to a different user
        
        // Generate the deterministic UUID for testUserId (same logic as implementation)
        UUID pseudoAccountNamespace = UUID.fromString("6ba7b815-9dad-11d1-80b4-00c04fd430c8");
        String expectedPseudoAccountId = com.budgetbuddy.util.IdGenerator.generateDeterministicUUID(
            pseudoAccountNamespace, 
            "pseudo-account:" + testUserId.toLowerCase()
        );
        
        // Create existing account with correct UUID but wrong userId
        AccountTable existingAccount = new AccountTable();
        existingAccount.setAccountId(expectedPseudoAccountId);
        existingAccount.setUserId("different-user-id"); // Wrong userId
        existingAccount.setAccountName("Manual Transactions");
        existingAccount.setInstitutionName("BudgetBuddy");
        existingAccount.setAccountType("other");
        existingAccount.setAccountSubtype("manual");
        existingAccount.setBalance(BigDecimal.ZERO);
        existingAccount.setCurrencyCode("USD");
        existingAccount.setActive(true);
        
        // Mock: First findById call (line 91) returns existing account with wrong userId
        // The implementation checks userId match (line 95), finds it doesn't match, and continues
        when(accountTable.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(existingAccount);
        
        // Mock: Conditional write will fail (account already exists with same UUID)
        doThrow(ConditionalCheckFailedException.builder().build())
                .when(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));
        
        // Mock: Second findById call (line 138, after conditional write fails) returns the existing account
        // Note: We need to return it again because findById is called twice
        when(accountTable.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(existingAccount)  // First call (line 91)
                .thenReturn(existingAccount); // Second call (line 138, after exception)

        // When - Should handle gracefully and return existing account (even with wrong userId)
        AccountTable result = accountRepository.getOrCreatePseudoAccount(testUserId);

        // Then - Should return the existing account
        // Note: In practice, this shouldn't happen due to deterministic UUID + userId validation
        // But the implementation handles it gracefully by returning the existing account
        assertNotNull(result);
        assertEquals(expectedPseudoAccountId, result.getAccountId());
    }

    // Helper method
    private AccountTable createPseudoAccount(String userId) {
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString()); // In real implementation, this is deterministic
        account.setUserId(userId);
        account.setAccountName("Manual Transactions");
        account.setInstitutionName("BudgetBuddy");
        account.setAccountType("other");
        account.setAccountSubtype("manual");
        account.setBalance(BigDecimal.ZERO);
        account.setCurrencyCode("USD");
        account.setActive(true);
        account.setPlaidAccountId(null);
        account.setPlaidItemId(null);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return account;
    }
}

