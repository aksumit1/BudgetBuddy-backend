package com.budgetbuddy.repository.dynamodb;

import com.budgetbuddy.model.dynamodb.AccountTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
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

    @InjectMocks
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

        // Setup mocks - Note: AccountRepository constructor needs to be called properly
        // For unit tests, we'll test the logic without full DynamoDB setup
        when(accountTable.index("UserIdIndex")).thenReturn(userIdIndex);
        when(accountTable.index("PlaidAccountIdIndex")).thenReturn(plaidAccountIdIndex);
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

    @SuppressWarnings("unchecked")
    private Page<AccountTable> createPage(List<AccountTable> items) {
        Page<AccountTable> page = mock(Page.class);
        when(page.items()).thenReturn(items);
        return page;
    }
}

