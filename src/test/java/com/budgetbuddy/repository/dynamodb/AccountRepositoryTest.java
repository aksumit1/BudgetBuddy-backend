package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.AccountTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AccountRepository
 * Tests account retrieval logic, especially the active field filtering
 */
@ExtendWith(MockitoExtension.class)
class AccountRepositoryTest {

    @Mock
    private DynamoDbEnhancedClient enhancedClient;

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Mock
    private DynamoDbTable<AccountTable> accountTable;

    @Mock
    private DynamoDbIndex<AccountTable> userIdIndex;

    @Mock
    private DynamoDbIndex<AccountTable> plaidAccountIdIndex;

    @Mock
    private DynamoDbIndex<AccountTable> plaidItemIdIndex;

    private AccountRepository accountRepository;

    private String testUserId;
    private AccountTable activeAccount;
    private AccountTable inactiveAccount;
    private AccountTable nullActiveAccount;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();

        // Create test accounts
        activeAccount = createAccount("account-1", testUserId, true);
        inactiveAccount = createAccount("account-2", testUserId, false);
        nullActiveAccount = createAccount("account-3", testUserId, null);

        // Setup mocks - AccountRepository constructor requires enhancedClient and dynamoDbClient
        when(enhancedClient.table(anyString(), any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(accountTable);
        when(accountTable.index("UserIdIndex")).thenReturn(userIdIndex);
        when(accountTable.index("PlaidAccountIdIndex")).thenReturn(plaidAccountIdIndex);
        when(accountTable.index("PlaidItemIdIndex")).thenReturn(plaidItemIdIndex);
        when(accountTable.index("UserIdUpdatedAtIndex")).thenReturn(mock(DynamoDbIndex.class));
        
        // Construct repository with mocks
        accountRepository = new AccountRepository(enhancedClient, dynamoDbClient, "TestBudgetBuddy");
    }

    @Test
    void testFindByUserId_WithActiveAccount_ReturnsAccount() {
        // Given
        Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(1, result.size());
        assertTrue(result.contains(activeAccount));
    }

    @Test
    void testFindByUserId_WithNullActiveAccount_ReturnsAccount() {
        // Given - Account with null active should be treated as active
        Page<AccountTable> page = createPage(Collections.singletonList(nullActiveAccount));
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(1, result.size(), "Accounts with null active should be returned");
        assertTrue(result.contains(nullActiveAccount));
    }

    @Test
    void testFindByUserId_WithInactiveAccount_ExcludesAccount() {
        // Given
        Page<AccountTable> page = createPage(Collections.singletonList(inactiveAccount));
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(0, result.size(), "Inactive accounts should be excluded");
        assertFalse(result.contains(inactiveAccount));
    }

    @Test
    void testFindByUserId_WithMixedAccounts_ReturnsOnlyActiveAndNull() {
        // Given
        List<AccountTable> allAccounts = Arrays.asList(activeAccount, inactiveAccount, nullActiveAccount);
        Page<AccountTable> page = createPage(allAccounts);
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(2, result.size(), "Should return active and null active accounts");
        assertTrue(result.contains(activeAccount));
        assertTrue(result.contains(nullActiveAccount));
        assertFalse(result.contains(inactiveAccount));
    }

    @Test
    void testFindByUserId_WithNoAccounts_ReturnsEmptyList() {
        // Given
        Page<AccountTable> emptyPage = createPage(Collections.emptyList());
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(emptyPage).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserId_WithMultiplePages_ReturnsAllActiveAccounts() {
        // Given
        AccountTable activeAccount2 = createAccount("account-4", testUserId, true);
        Page<AccountTable> page1 = createPage(Collections.singletonList(activeAccount));
        Page<AccountTable> page2 = createPage(Collections.singletonList(activeAccount2));
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Arrays.asList(page1, page2).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(2, result.size());
        assertTrue(result.contains(activeAccount));
        assertTrue(result.contains(activeAccount2));
    }

    @Test
    void testFindByPlaidAccountId_WithExistingAccount_ReturnsAccount() {
        // Given
        String plaidAccountId = "plaid-account-123";
        activeAccount.setPlaidAccountId(plaidAccountId);
        Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(plaidAccountIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        Optional<AccountTable> result = accountRepository.findByPlaidAccountId(plaidAccountId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(plaidAccountId, result.get().getPlaidAccountId());
    }

    @Test
    void testFindByPlaidAccountId_WithNonExistentAccount_ReturnsEmpty() {
        // Given
        Page<AccountTable> emptyPage = createPage(Collections.emptyList());
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(emptyPage).iterator());
        when(plaidAccountIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        Optional<AccountTable> result = accountRepository.findByPlaidAccountId("non-existent");

        // Then
        assertFalse(result.isPresent());
    }

    // Helper methods
    private AccountTable createAccount(String accountId, String userId, Boolean active) {
        AccountTable account = new AccountTable();
        account.setAccountId(accountId);
        account.setUserId(userId);
        account.setAccountName("Test Account " + accountId);
        account.setInstitutionName("Test Bank");
        account.setAccountType("CHECKING");
        account.setBalance(new BigDecimal("1000.00"));
        account.setCurrencyCode("USD");
        account.setActive(active);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return account;
    }

    @Test
    void testFindByAccountNumber_WithExistingAccount_ReturnsAccount() {
        // Given
        String accountNumber = "1234567890";
        activeAccount.setAccountNumber(accountNumber);
        Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        Optional<AccountTable> result = accountRepository.findByAccountNumber(accountNumber, testUserId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(accountNumber, result.get().getAccountNumber());
    }

    @Test
    void testFindByAccountNumber_WithNullAccountNumber_ReturnsEmpty() {
        // When
        Optional<AccountTable> result = accountRepository.findByAccountNumber(null, testUserId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByAccountNumber_WithEmptyAccountNumber_ReturnsEmpty() {
        // When
        Optional<AccountTable> result = accountRepository.findByAccountNumber("", testUserId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByPlaidItemId_WithExistingItem_ReturnsAccounts() {
        // Given
        String plaidItemId = "item-123";
        activeAccount.setPlaidItemId(plaidItemId);
        inactiveAccount.setPlaidItemId(plaidItemId);
        
        // Create a mock page with the accounts using the helper method
        Page<AccountTable> page = createPage(Arrays.asList(activeAccount, inactiveAccount));
        @SuppressWarnings("unchecked")
        SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(plaidItemIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        List<AccountTable> result = accountRepository.findByPlaidItemId(plaidItemId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains(activeAccount));
        assertTrue(result.contains(inactiveAccount));
    }

    @Test
    void testFindByPlaidItemId_WithNullItemId_ReturnsEmpty() {
        // When
        List<AccountTable> result = accountRepository.findByPlaidItemId(null);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByPlaidItemId_WithEmptyItemId_ReturnsEmpty() {
        // When
        List<AccountTable> result = accountRepository.findByPlaidItemId("");

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testSaveIfNotExists_WithNewAccount_ReturnsTrue() {
        // Given
        AccountTable newAccount = createAccount("new-account", testUserId, true);
        doNothing().when(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));

        // When
        boolean result = accountRepository.saveIfNotExists(newAccount);

        // Then
        assertTrue(result);
        verify(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));
    }

    @Test
    void testSaveIfNotExists_WithExistingAccount_ReturnsFalse() {
        // Given
        AccountTable existingAccount = createAccount("existing-account", testUserId, true);
        doThrow(software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException.builder().build())
                .when(accountTable).putItem(any(software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest.class));

        // When
        boolean result = accountRepository.saveIfNotExists(existingAccount);

        // Then
        assertFalse(result);
    }

    @Test
    void testSaveIfNotExists_WithNullAccount_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            accountRepository.saveIfNotExists(null);
        });
    }

    @Test
    void testSaveIfNotExists_WithNullAccountId_ThrowsException() {
        // Given
        AccountTable account = createAccount("test", testUserId, true);
        account.setAccountId(null);

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            accountRepository.saveIfNotExists(account);
        });
    }

    @Test
    void testBatchSave_WithValidAccounts_SavesAll() {
        // Given
        List<AccountTable> accounts = Arrays.asList(activeAccount, inactiveAccount);
        when(dynamoDbClient.batchWriteItem(any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class)))
                .thenReturn(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse.builder().build());

        // When
        accountRepository.batchSave(accounts);

        // Then
        verify(dynamoDbClient, atLeastOnce()).batchWriteItem(any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class));
    }

    @Test
    void testBatchSave_WithEmptyList_DoesNothing() {
        // When
        accountRepository.batchSave(Collections.emptyList());

        // Then
        verify(dynamoDbClient, never()).batchWriteItem(any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class));
    }

    @Test
    void testBatchSave_WithNullList_DoesNothing() {
        // When
        accountRepository.batchSave(null);

        // Then
        verify(dynamoDbClient, never()).batchWriteItem(any(software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest.class));
    }

    @SuppressWarnings("unchecked")
    private Page<AccountTable> createPage(List<AccountTable> items) {
        Page<AccountTable> page = mock(Page.class);
        when(page.items()).thenReturn(items);
        return page;
    }
}

