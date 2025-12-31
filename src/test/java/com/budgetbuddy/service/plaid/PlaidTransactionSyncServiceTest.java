package com.budgetbuddy.service.plaid;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.plaid.PlaidService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.PlaidCategoryMapper;
import com.plaid.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PlaidTransactionSyncService
 * Tests transaction synchronization logic
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaidTransactionSyncServiceTest {

    @Mock
    private PlaidService plaidService;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PlaidCategoryMapper categoryMapper;

    private PlaidDataExtractor dataExtractor;
    private PlaidTransactionSyncService transactionSyncService;

    private UserTable testUser;
    private String testAccessToken;
    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
        testAccessToken = "test-access-token";
        
        // Create real PlaidDataExtractor instance (can't be mocked due to Spring @Component)
        dataExtractor = new PlaidDataExtractor(accountRepository, org.mockito.Mockito.mock(com.budgetbuddy.service.TransactionTypeCategoryService.class));
        
        // Create PlaidTransactionSyncService with real dataExtractor
        // Note: PlaidTransactionSyncService constructor doesn't include PlaidCategoryMapper
        transactionSyncService = new PlaidTransactionSyncService(
            plaidService,
            accountRepository,
            transactionRepository,
            dataExtractor
        );
    }

    @Test
    void testSyncTransactions_WithValidData_CreatesTransactions() {
        // Given
        AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(null); // First sync

        TransactionsGetResponse transactionsResponse = createMockTransactionsResponse();
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.singletonList(testAccount));
        when(plaidService.getTransactions(eq(testAccessToken), anyString(), anyString()))
                .thenReturn(transactionsResponse);
        when(transactionRepository.findByPlaidTransactionId(anyString())).thenReturn(Optional.empty());
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class))).thenReturn(true);
        
        // Mock category mapper to return a valid CategoryMapping
        PlaidCategoryMapper.CategoryMapping mockMapping = new PlaidCategoryMapper.CategoryMapping("other", "other", false);
        when(categoryMapper.mapPlaidCategory(any(), any(), any(), any(), any(), any()))
                .thenReturn(mockMapping);
        when(categoryMapper.mapPlaidCategory(any(), any(), any(), any()))
                .thenReturn(mockMapping);

        // When
        assertDoesNotThrow(() -> transactionSyncService.syncTransactions(testUser, testAccessToken));

        // Then
        verify(plaidService, atLeastOnce()).getTransactions(eq(testAccessToken), anyString(), anyString());
        verify(transactionRepository, atLeastOnce()).saveIfPlaidTransactionNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncTransactions_WithNullUser_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            transactionSyncService.syncTransactions(null, testAccessToken);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("User cannot be null"));
    }

    @Test
    void testSyncTransactions_WithNullAccessToken_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            transactionSyncService.syncTransactions(testUser, null);
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Access token cannot be null or empty"));
    }

    @Test
    void testSyncTransactions_WithEmptyAccessToken_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            transactionSyncService.syncTransactions(testUser, "");
        });

        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("Access token cannot be null or empty"));
    }

    @Test
    void testSyncTransactions_WithNoAccounts_DoesNotCallPlaid() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());

        // When
        assertDoesNotThrow(() -> transactionSyncService.syncTransactions(testUser, testAccessToken));

        // Then
        verify(plaidService, never()).getTransactions(anyString(), anyString(), anyString());
    }

    @Test
    void testSyncTransactions_WithRecentlySyncedAccount_SkipsSync() {
        // Given
        AccountTable testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidAccountId("plaid-account-1");
        testAccount.setLastSyncedAt(Instant.now().minusSeconds(60)); // Synced 1 minute ago

        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.singletonList(testAccount));

        // When
        assertDoesNotThrow(() -> transactionSyncService.syncTransactions(testUser, testAccessToken));

        // Then
        verify(plaidService, never()).getTransactions(anyString(), anyString(), anyString());
    }

    private TransactionsGetResponse createMockTransactionsResponse() {
        TransactionsGetResponse response = new TransactionsGetResponse();
        
        Transaction transaction = new Transaction();
        transaction.setTransactionId("plaid-txn-1");
        transaction.setAccountId("plaid-account-1");
        transaction.setAmount(100.0);
        transaction.setName("Test Transaction");
        transaction.setDate(LocalDate.now());
        transaction.setIsoCurrencyCode("USD");
        
        response.setTransactions(Collections.singletonList(transaction));
        response.setTotalTransactions(1);
        
        return response;
    }
}

