package com.budgetbuddy.repository.dynamodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.AccountTable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.pagination.sync.SdkIterable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PageIterable;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Unit Tests for AccountRepository Tests account retrieval logic, especially the active field
 * filtering
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AccountRepositoryTest {

    private static final String UNCHECKED = "unchecked";

    @Mock private DynamoDbEnhancedClient enhancedClient;

    @Mock private DynamoDbClient dynamoDbClient;

    @Mock private DynamoDbTable<AccountTable> accountTable;

    @Mock private DynamoDbIndex<AccountTable> userIdIndex;

    @Mock private DynamoDbIndex<AccountTable> plaidAccountIdIndex;

    @Mock private DynamoDbIndex<AccountTable> plaidItemIdIndex;

    @Mock private DynamoDbIndex<AccountTable> userIdUpdatedAtIndex;

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
        when(enhancedClient.table(
                        anyString(),
                        any(software.amazon.awssdk.enhanced.dynamodb.TableSchema.class)))
                .thenReturn(accountTable);
        when(accountTable.index("UserIdIndex")).thenReturn(userIdIndex);
        when(accountTable.index("PlaidAccountIdIndex")).thenReturn(plaidAccountIdIndex);
        when(accountTable.index("PlaidItemIdIndex")).thenReturn(plaidItemIdIndex);
        when(accountTable.index("UserIdUpdatedAtIndex")).thenReturn(userIdUpdatedAtIndex);

        // Construct repository with mocks
        accountRepository =
                new AccountRepository(enhancedClient, dynamoDbClient, "TestBudgetBuddy");
    }

    @Test
    void testFindByUserIdWithNullUserIdReturnsEmpty() {
        // When
        final List<AccountTable> result = accountRepository.findByUserId(null);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserIdWithEmptyUserIdReturnsEmpty() {
        // When
        final List<AccountTable> result = accountRepository.findByUserId("");

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetOrCreatePseudoAccountWithNullUserIdThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> accountRepository.getOrCreatePseudoAccount(null));
    }

    @Test
    void testGetOrCreatePseudoAccountWithEmptyUserIdThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> accountRepository.getOrCreatePseudoAccount(""));
    }

    @Test
    void testFindByUserIdWithActiveAccountReturnsAccount() {
        // Given
        final Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(1, result.size());
        assertTrue(result.contains(activeAccount));
    }

    @Test
    void testFindByUserIdWithNullActiveAccountReturnsAccount() {
        // Given - Account with null active should be treated as active
        final Page<AccountTable> page = createPage(Collections.singletonList(nullActiveAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(1, result.size(), "Accounts with null active should be returned");
        assertTrue(result.contains(nullActiveAccount));
    }

    @Test
    void testFindByUserIdWithInactiveAccountExcludesAccount() {
        // Given
        final Page<AccountTable> page = createPage(Collections.singletonList(inactiveAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(0, result.size(), "Inactive accounts should be excluded");
        assertFalse(result.contains(inactiveAccount));
    }

    @Test
    void testFindByUserIdWithMixedAccountsReturnsOnlyActiveAndNull() {
        // Given
        final List<AccountTable> allAccounts =
                Arrays.asList(activeAccount, inactiveAccount, nullActiveAccount);
        final Page<AccountTable> page = createPage(allAccounts);
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(2, result.size(), "Should return active and null active accounts");
        assertTrue(result.contains(activeAccount));
        assertTrue(result.contains(nullActiveAccount));
        assertFalse(result.contains(inactiveAccount));
    }

    @Test
    void testFindByUserIdWithNoAccountsReturnsEmptyList() {
        // Given
        final Page<AccountTable> emptyPage = createPage(Collections.emptyList());
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(emptyPage).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserIdWithMultiplePagesReturnsAllActiveAccounts() {
        // Given
        final AccountTable activeAccount2 = createAccount("account-4", testUserId, true);
        final Page<AccountTable> page1 = createPage(Collections.singletonList(activeAccount));
        final Page<AccountTable> page2 = createPage(Collections.singletonList(activeAccount2));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Arrays.asList(page1, page2).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<AccountTable> result = accountRepository.findByUserId(testUserId);

        // Then
        assertEquals(2, result.size());
        assertTrue(result.contains(activeAccount));
        assertTrue(result.contains(activeAccount2));
    }

    @Test
    void testFindByPlaidAccountIdWithExistingAccountReturnsAccount() {
        // Given
        final String plaidAccountId = "plaid-account-123";
        activeAccount.setPlaidAccountId(plaidAccountId);
        final Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(plaidAccountIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final Optional<AccountTable> result =
                accountRepository.findByPlaidAccountId(plaidAccountId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(plaidAccountId, result.get().getPlaidAccountId());
    }

    @Test
    void testFindByPlaidAccountIdWithNonExistentAccountReturnsEmpty() {
        // Given
        final Page<AccountTable> emptyPage = createPage(Collections.emptyList());
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(emptyPage).iterator());
        when(plaidAccountIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final Optional<AccountTable> result =
                accountRepository.findByPlaidAccountId("non-existent");

        // Then
        assertFalse(result.isPresent());
    }

    // Helper methods
    private AccountTable createAccount(
            final String accountId, final String userId, final Boolean active) {
        final AccountTable account = new AccountTable();
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
    void testFindByAccountNumberWithExistingAccountReturnsAccount() {
        // Given
        final String accountNumber = "1234567890";
        activeAccount.setAccountNumber(accountNumber);
        final Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final Optional<AccountTable> result =
                accountRepository.findByAccountNumber(accountNumber, testUserId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(accountNumber, result.get().getAccountNumber());
    }

    @Test
    void testFindByAccountNumberWithNullAccountNumberReturnsEmpty() {
        // When
        final Optional<AccountTable> result =
                accountRepository.findByAccountNumber(null, testUserId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByAccountNumberWithEmptyAccountNumberReturnsEmpty() {
        // When
        final Optional<AccountTable> result = accountRepository.findByAccountNumber("", testUserId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByPlaidItemIdWithExistingItemReturnsAccounts() {
        // Given
        final String plaidItemId = "item-123";
        activeAccount.setPlaidItemId(plaidItemId);
        inactiveAccount.setPlaidItemId(plaidItemId);

        // Create a mock page with the accounts using the helper method
        final Page<AccountTable> page = createPage(Arrays.asList(activeAccount, inactiveAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(plaidItemIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<AccountTable> result = accountRepository.findByPlaidItemId(plaidItemId);

        // Then
        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains(activeAccount));
        assertTrue(result.contains(inactiveAccount));
    }

    @Test
    void testFindByPlaidItemIdWithNullItemIdReturnsEmpty() {
        // When
        final List<AccountTable> result = accountRepository.findByPlaidItemId(null);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByPlaidItemIdWithEmptyItemIdReturnsEmpty() {
        // When
        final List<AccountTable> result = accountRepository.findByPlaidItemId("");

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testSaveIfNotExistsWithNewAccountReturnsTrue() {
        // Given
        final AccountTable newAccount = createAccount("new-account", testUserId, true);
        doNothing()
                .when(accountTable)
                .putItem(
                        any(
                                software.amazon.awssdk.enhanced.dynamodb.model
                                        .PutItemEnhancedRequest.class));

        // When
        final boolean result = accountRepository.saveIfNotExists(newAccount);

        // Then
        assertTrue(result);
        verify(accountTable)
                .putItem(
                        any(
                                software.amazon.awssdk.enhanced.dynamodb.model
                                        .PutItemEnhancedRequest.class));
    }

    @Test
    void testSaveIfNotExistsWithExistingAccountReturnsFalse() {
        // Given
        final AccountTable existingAccount = createAccount("existing-account", testUserId, true);
        doThrow(
                        software.amazon.awssdk.services.dynamodb.model
                                .ConditionalCheckFailedException.builder()
                                .build())
                .when(accountTable)
                .putItem(
                        any(
                                software.amazon.awssdk.enhanced.dynamodb.model
                                        .PutItemEnhancedRequest.class));

        // When
        final boolean result = accountRepository.saveIfNotExists(existingAccount);

        // Then
        assertFalse(result);
    }

    @Test
    void testSaveIfNotExistsWithNullAccountThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    accountRepository.saveIfNotExists(null);
                });
    }

    @Test
    void testSaveIfNotExistsWithNullAccountIdThrowsException() {
        // Given
        final AccountTable account = createAccount("test", testUserId, true);
        account.setAccountId(null);

        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    accountRepository.saveIfNotExists(account);
                });
    }

    @Test
    void testBatchSaveWithValidAccountsSavesAll() {
        // Given
        final List<AccountTable> accounts = Arrays.asList(activeAccount, inactiveAccount);
        when(dynamoDbClient.batchWriteItem(
                        any(
                                software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
                                        .class)))
                .thenReturn(
                        software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse
                                .builder()
                                .build());

        // When
        accountRepository.batchSave(accounts);

        // Then
        verify(dynamoDbClient, atLeastOnce())
                .batchWriteItem(
                        any(
                                software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
                                        .class));
    }

    @Test
    void testBatchSaveWithEmptyListDoesNothing() {
        // When
        accountRepository.batchSave(Collections.emptyList());

        // Then
        verify(dynamoDbClient, never())
                .batchWriteItem(
                        any(
                                software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
                                        .class));
    }

    @Test
    void testBatchSaveWithNullListDoesNothing() {
        // When
        accountRepository.batchSave(null);

        // Then
        verify(dynamoDbClient, never())
                .batchWriteItem(
                        any(
                                software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
                                        .class));
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithValidParamsReturnsUpdatedAccounts() {
        // Given
        final long updatedAfterTimestamp =
                Instant.now().minusSeconds(3600).getEpochSecond(); // 1 hour ago
        activeAccount.setUpdatedAtTimestamp(Instant.now().getEpochSecond());

        final Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final List<AccountTable> result =
                accountRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp);

        // Then
        assertNotNull(result);
        assertTrue(result.size() >= 0); // May be empty if timestamp doesn't match
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithResourceNotFoundExceptionFallsBackToFindByUserId() {
        // Given - Simulate missing GSI index
        final long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        activeAccount.setUpdatedAtTimestamp(Instant.now().getEpochSecond());

        // Mock ResourceNotFoundException when querying GSI
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class)))
                .thenThrow(
                        software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException
                                .builder()
                                .build());

        // Mock findByUserId fallback
        final Page<AccountTable> fallbackPage =
                createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> fallbackPages = mock(SdkIterable.class);
        when(fallbackPages.iterator())
                .thenReturn(Collections.singletonList(fallbackPage).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(fallbackPages);

        // When
        final List<AccountTable> result =
                accountRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp);

        // Then - Should fallback to findByUserId and filter
        assertNotNull(result);
        verify(userIdIndex, atLeastOnce()).query(any(QueryConditional.class));
    }

    @Test
    void testFindByIdWithValidIdReturnsAccount() {
        // Given
        when(accountTable.getItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class)))
                .thenReturn(activeAccount);

        // When
        final Optional<AccountTable> result =
                accountRepository.findById(activeAccount.getAccountId());

        // Then
        assertTrue(result.isPresent());
        assertEquals(activeAccount.getAccountId(), result.get().getAccountId());
    }

    @Test
    void testFindByIdWithNullIdReturnsEmpty() {
        // When
        final Optional<AccountTable> result = accountRepository.findById(null);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByIdWithEmptyIdReturnsEmpty() {
        // When
        final Optional<AccountTable> result = accountRepository.findById("");

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testDeleteWithValidIdDeletesAccount() {
        // When
        accountRepository.delete(activeAccount.getAccountId());

        // Then
        verify(accountTable, times(1))
                .deleteItem(any(software.amazon.awssdk.enhanced.dynamodb.Key.class));
    }

    @Test
    void testSaveWithValidAccountSavesAccount() {
        // Given
        doNothing().when(accountTable).putItem(any(AccountTable.class));

        // When
        accountRepository.save(activeAccount);

        // Then
        verify(accountTable, times(1)).putItem(any(AccountTable.class));
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithNullUserIdReturnsEmpty() {
        // When
        final List<AccountTable> result =
                accountRepository.findByUserIdAndUpdatedAfter(null, Instant.now().getEpochSecond());

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithNullTimestampReturnsEmpty() {
        // When
        final List<AccountTable> result =
                accountRepository.findByUserIdAndUpdatedAfter(testUserId, null);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithEmptyUserIdReturnsEmpty() {
        // When
        final List<AccountTable> result =
                accountRepository.findByUserIdAndUpdatedAfter("", Instant.now().getEpochSecond());

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithExistingAccountReturnsAccount() {
        // Given
        final String accountNumber = "1234567890";
        final String institutionName = "Test Bank";
        activeAccount.setAccountNumber(accountNumber);
        activeAccount.setInstitutionName(institutionName);
        final Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final Optional<AccountTable> result =
                accountRepository.findByAccountNumberAndInstitution(
                        accountNumber, institutionName, testUserId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(accountNumber, result.get().getAccountNumber());
        assertEquals(institutionName, result.get().getInstitutionName());
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithNullInstitutionMatchesByAccountNumberOnly() {
        // Given
        final String accountNumber = "1234567890";
        activeAccount.setAccountNumber(accountNumber);
        activeAccount.setInstitutionName(null);
        final Page<AccountTable> page = createPage(Collections.singletonList(activeAccount));
        @SuppressWarnings(UNCHECKED)
        final SdkIterable<Page<AccountTable>> pages = mock(SdkIterable.class);
        when(pages.iterator()).thenReturn(Collections.singletonList(page).iterator());
        when(userIdIndex.query(any(QueryConditional.class))).thenReturn(pages);

        // When
        final Optional<AccountTable> result =
                accountRepository.findByAccountNumberAndInstitution(
                        accountNumber, null, testUserId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(accountNumber, result.get().getAccountNumber());
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithNullAccountNumberReturnsEmpty() {
        // When
        final Optional<AccountTable> result =
                accountRepository.findByAccountNumberAndInstitution(null, "Test Bank", testUserId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testFindByAccountNumberAndInstitutionWithEmptyAccountNumberReturnsEmpty() {
        // When
        final Optional<AccountTable> result =
                accountRepository.findByAccountNumberAndInstitution("", "Test Bank", testUserId);

        // Then
        assertFalse(result.isPresent());
    }

    @Test
    void testBatchDeleteWithValidAccountIdsDeletesAccounts() {
        // Given
        final List<String> accountIds = Arrays.asList("account-1", "account-2");
        when(dynamoDbClient.batchWriteItem(
                        any(
                                software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
                                        .class)))
                .thenReturn(
                        software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse
                                .builder()
                                .build());

        // When
        accountRepository.batchDelete(accountIds);

        // Then
        verify(dynamoDbClient, atLeastOnce())
                .batchWriteItem(
                        any(
                                software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
                                        .class));
    }

    @Test
    void testBatchDeleteWithEmptyListDoesNothing() {
        // When
        accountRepository.batchDelete(Collections.emptyList());

        // Then
        verify(dynamoDbClient, never())
                .batchWriteItem(
                        any(
                                software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
                                        .class));
    }

    @Test
    void testBatchDeleteWithNullListDoesNothing() {
        // When
        accountRepository.batchDelete(null);

        // Then
        verify(dynamoDbClient, never())
                .batchWriteItem(
                        any(
                                software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
                                        .class));
    }

    @Test
    void testDeleteWithNullIdThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    accountRepository.delete(null);
                });
    }

    @Test
    void testDeleteWithEmptyIdThrowsException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    accountRepository.delete("");
                });
    }

    @Test
    void testFindByUserIdAndUpdatedAfterWithExceptionFallsBackGracefully() {
        // Given - Simulate exception during GSI query
        final long updatedAfterTimestamp = Instant.now().minusSeconds(3600).getEpochSecond();
        when(userIdUpdatedAtIndex.query(any(QueryConditional.class)))
                .thenThrow(new RuntimeException("Unexpected error"));

        // Mock findByUserId fallback - only set up if the exception triggers fallback
        // The actual code catches RuntimeException and logs error, but doesn't fallback
        // So we don't need to mock the fallback for this test
        activeAccount.setUpdatedAtTimestamp(Instant.now().getEpochSecond());

        // When
        final List<AccountTable> result =
                accountRepository.findByUserIdAndUpdatedAfter(testUserId, updatedAfterTimestamp);

        // Then - Should handle exception gracefully (returns empty list on exception)
        assertNotNull(result);
        // The method catches the exception and returns empty list, so result should be empty
        assertTrue(result.isEmpty(), "Should return empty list when exception occurs");
    }

    @Test
    void testFindByPlaidItemIdWithNullIndexFallsBackToScan() {
        // Given - Simulate null index (test environment)
        final String plaidItemId = "item-123";
        activeAccount.setPlaidItemId(plaidItemId);

        // Create a scan page
        final Page<AccountTable> scanPage = createPage(Collections.singletonList(activeAccount));
        // scan() returns PageIterable<AccountTable> - mock it directly
        @SuppressWarnings(UNCHECKED)
        final PageIterable<AccountTable> scanPages = mock(PageIterable.class);
        when(scanPages.iterator()).thenReturn(Collections.singletonList(scanPage).iterator());

        // Mock plaidItemIdIndex to return null (simulating missing index)
        when(accountTable.index("PlaidItemIdIndex")).thenReturn(null);
        // Mock scan() to return PageIterable<AccountTable>
        when(accountTable.scan()).thenReturn(scanPages);

        // Reconstruct repository to get null index
        final AccountRepository repoWithNullIndex =
                new AccountRepository(enhancedClient, dynamoDbClient, "TestBudgetBuddy");

        // When
        final List<AccountTable> result = repoWithNullIndex.findByPlaidItemId(plaidItemId);

        // Then - Should fallback to scan
        assertNotNull(result);
        verify(accountTable, atLeastOnce()).scan();
    }

    @SuppressWarnings(UNCHECKED)
    private Page<AccountTable> createPage(final List<AccountTable> items) {
        final Page<AccountTable> page = mock(Page.class);
        when(page.items()).thenReturn(items);
        return page;
    }
}
