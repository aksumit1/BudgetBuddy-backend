package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for TransactionSyncHelper Tests transaction synchronization logic */
@ExtendWith(MockitoExtension.class)
class TransactionSyncHelperTest {

    @Mock private TransactionRepository transactionRepository;

    @InjectMocks private TransactionSyncHelper transactionSyncHelper;

    private TransactionTable testTransaction;
    private String testPlaidTransactionId;

    @BeforeEach
    void setUp() {
        transactionSyncHelper = new TransactionSyncHelper(transactionRepository);
        testPlaidTransactionId = "plaid-txn-123";

        testTransaction = new TransactionTable();
        testTransaction.setTransactionId("txn-123");
        testTransaction.setPlaidTransactionId(testPlaidTransactionId);
        testTransaction.setAmount(new BigDecimal("100.00"));
    }

    @Test
    void testSyncSingleTransactionWithNullTransactionReturnsError() {
        // When
        final TransactionSyncHelper.SyncResult result =
                transactionSyncHelper.syncSingleTransaction(null, testPlaidTransactionId);

        // Then
        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getNewCount());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void testSyncSingleTransactionWithNullPlaidIdReturnsError() {
        // When
        final TransactionSyncHelper.SyncResult result =
                transactionSyncHelper.syncSingleTransaction(testTransaction, null);

        // Then
        assertEquals(1, result.getErrorCount());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void testSyncSingleTransactionWithNewTransactionReturnsNewCount() {
        // Given
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(true);

        // When
        final TransactionSyncHelper.SyncResult result =
                transactionSyncHelper.syncSingleTransaction(
                        testTransaction, testPlaidTransactionId);

        // Then
        assertEquals(1, result.getNewCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getErrorCount());
        verify(transactionRepository, times(1))
                .saveIfPlaidTransactionNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncSingleTransactionWithExistingTransactionReturnsUpdatedCount() {
        // Given
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenReturn(false);
        when(transactionRepository.findByPlaidTransactionId(testPlaidTransactionId))
                .thenReturn(Optional.of(testTransaction));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        final TransactionSyncHelper.SyncResult result =
                transactionSyncHelper.syncSingleTransaction(
                        testTransaction, testPlaidTransactionId);

        // Then
        assertEquals(0, result.getNewCount());
        assertEquals(1, result.getUpdatedCount());
        assertEquals(0, result.getErrorCount());
        verify(transactionRepository, times(1)).findByPlaidTransactionId(testPlaidTransactionId);
    }

    @Test
    void testSyncSingleTransactionWithNoPlaidIdUsesRegularSave() {
        // Given
        testTransaction.setPlaidTransactionId(null);
        when(transactionRepository.saveIfNotExists(any(TransactionTable.class))).thenReturn(true);

        // When
        final TransactionSyncHelper.SyncResult result =
                transactionSyncHelper.syncSingleTransaction(
                        testTransaction, testPlaidTransactionId);

        // Then
        assertEquals(1, result.getNewCount());
        verify(transactionRepository, times(1)).saveIfNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncSingleTransactionWithExceptionReturnsError() {
        // Given
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenThrow(new RuntimeException("Database error"));

        // When
        final TransactionSyncHelper.SyncResult result =
                transactionSyncHelper.syncSingleTransaction(
                        testTransaction, testPlaidTransactionId);

        // Then
        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getNewCount());
    }
}
