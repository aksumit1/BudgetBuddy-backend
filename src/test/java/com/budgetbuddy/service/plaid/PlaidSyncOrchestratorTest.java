package com.budgetbuddy.service.plaid;

import com.budgetbuddy.model.dynamodb.UserTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PlaidSyncOrchestrator
 * Tests orchestration of account and transaction synchronization
 */
@ExtendWith(MockitoExtension.class)
class PlaidSyncOrchestratorTest {

    @Mock
    private PlaidAccountSyncService accountSyncService;

    @Mock
    private PlaidTransactionSyncService transactionSyncService;

    @InjectMocks
    private PlaidSyncOrchestrator orchestrator;

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
    void testSyncAll_CallsBothServices() {
        // When
        assertDoesNotThrow(() -> orchestrator.syncAll(testUser, testAccessToken, testItemId));

        // Then
        verify(accountSyncService, times(1)).syncAccounts(testUser, testAccessToken, testItemId);
        verify(transactionSyncService, times(1)).syncTransactions(testUser, testAccessToken);
    }

    @Test
    void testSyncAccountsOnly_CallsAccountServiceOnly() {
        // When
        assertDoesNotThrow(() -> orchestrator.syncAccountsOnly(testUser, testAccessToken, testItemId));

        // Then
        verify(accountSyncService, times(1)).syncAccounts(testUser, testAccessToken, testItemId);
        verify(transactionSyncService, never()).syncTransactions(any(), any());
    }

    @Test
    void testSyncTransactionsOnly_CallsTransactionServiceOnly() {
        // When
        assertDoesNotThrow(() -> orchestrator.syncTransactionsOnly(testUser, testAccessToken));

        // Then
        verify(accountSyncService, never()).syncAccounts(any(), any(), any());
        verify(transactionSyncService, times(1)).syncTransactions(testUser, testAccessToken);
    }

    @Test
    void testSyncAll_WithNullUser_PropagatesException() {
        // When/Then - PlaidSyncOrchestrator will throw NullPointerException when accessing user.getUserId()
        // This is expected behavior - the orchestrator doesn't validate null before logging
        assertThrows(NullPointerException.class, () -> {
            orchestrator.syncAll(null, testAccessToken, testItemId);
        });
    }
}

