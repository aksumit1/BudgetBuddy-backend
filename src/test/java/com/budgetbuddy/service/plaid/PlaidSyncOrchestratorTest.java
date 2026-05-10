package com.budgetbuddy.service.plaid;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.budgetbuddy.model.dynamodb.UserTable;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit Tests for PlaidSyncOrchestrator Tests orchestration of account and transaction
 * synchronization
 */
@ExtendWith(MockitoExtension.class)
class PlaidSyncOrchestratorTest {

    @Mock private PlaidAccountSyncService accountSyncService;

    @Mock private PlaidTransactionSyncService transactionSyncService;

    @InjectMocks private PlaidSyncOrchestrator orchestrator;

    private UserTable testUser;
    private String testAccessToken;
    private String testItemId;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");
        testAccessToken = "test-access-token";
        testItemId = "test-item-id";
    }

    @Test
    void testSyncAllCallsBothServices() {
        // When
        assertDoesNotThrow(() -> orchestrator.syncAll(testUser, testAccessToken, testItemId));

        // Then
        verify(accountSyncService, times(1)).syncAccounts(testUser, testAccessToken, testItemId);
        verify(transactionSyncService, times(1)).syncTransactions(testUser, testAccessToken);
    }

    @Test
    void testSyncAccountsOnlyCallsAccountServiceOnly() {
        // When
        assertDoesNotThrow(
                () -> orchestrator.syncAccountsOnly(testUser, testAccessToken, testItemId));

        // Then
        verify(accountSyncService, times(1)).syncAccounts(testUser, testAccessToken, testItemId);
        verify(transactionSyncService, never()).syncTransactions(any(), any());
    }

    @Test
    void testSyncTransactionsOnlyCallsTransactionServiceOnly() {
        // When
        assertDoesNotThrow(() -> orchestrator.syncTransactionsOnly(testUser, testAccessToken));

        // Then
        verify(accountSyncService, never()).syncAccounts(any(), any(), any());
        verify(transactionSyncService, times(1)).syncTransactions(testUser, testAccessToken);
    }

    @Test
    void testSyncAllWithNullUserPropagatesException() {
        // When/Then - PlaidSyncOrchestrator will throw NullPointerException when accessing
        // user.getUserId()
        // This is expected behavior - the orchestrator doesn't validate null before logging
        assertThrows(
                NullPointerException.class,
                () -> {
                    orchestrator.syncAll(null, testAccessToken, testItemId);
                });
    }
}
