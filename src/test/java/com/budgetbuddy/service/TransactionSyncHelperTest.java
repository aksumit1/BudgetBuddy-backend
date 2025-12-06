package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for TransactionSyncHelper
 * Tests transaction synchronization logic
 */
@ExtendWith(MockitoExtension.class)
class TransactionSyncHelperTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionSyncHelper transactionSyncHelper;

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
    void testSyncSingleTransaction_WithNullTransaction_ReturnsError() {
        // When
        TransactionSyncHelper.SyncResult result = transactionSyncHelper.syncSingleTransaction(null, testPlaidTransactionId);

        // Then
        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getNewCount());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void testSyncSingleTransaction_WithNullPlaidId_ReturnsError() {
        // When
        TransactionSyncHelper.SyncResult result = transactionSyncHelper.syncSingleTransaction(testTransaction, null);

        // Then
        assertEquals(1, result.getErrorCount());
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void testSyncSingleTransaction_WithNewTransaction_ReturnsNewCount() {
        // Given
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class))).thenReturn(true);

        // When
        TransactionSyncHelper.SyncResult result = transactionSyncHelper.syncSingleTransaction(testTransaction, testPlaidTransactionId);

        // Then
        assertEquals(1, result.getNewCount());
        assertEquals(0, result.getUpdatedCount());
        assertEquals(0, result.getErrorCount());
        verify(transactionRepository, times(1)).saveIfPlaidTransactionNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncSingleTransaction_WithExistingTransaction_ReturnsUpdatedCount() {
        // Given
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class))).thenReturn(false);
        when(transactionRepository.findByPlaidTransactionId(testPlaidTransactionId))
                .thenReturn(Optional.of(testTransaction));
        doNothing().when(transactionRepository).save(any(TransactionTable.class));

        // When
        TransactionSyncHelper.SyncResult result = transactionSyncHelper.syncSingleTransaction(testTransaction, testPlaidTransactionId);

        // Then
        assertEquals(0, result.getNewCount());
        assertEquals(1, result.getUpdatedCount());
        assertEquals(0, result.getErrorCount());
        verify(transactionRepository, times(1)).findByPlaidTransactionId(testPlaidTransactionId);
    }

    @Test
    void testSyncSingleTransaction_WithNoPlaidId_UsesRegularSave() {
        // Given
        testTransaction.setPlaidTransactionId(null);
        when(transactionRepository.saveIfNotExists(any(TransactionTable.class))).thenReturn(true);

        // When
        TransactionSyncHelper.SyncResult result = transactionSyncHelper.syncSingleTransaction(testTransaction, testPlaidTransactionId);

        // Then
        assertEquals(1, result.getNewCount());
        verify(transactionRepository, times(1)).saveIfNotExists(any(TransactionTable.class));
    }

    @Test
    void testSyncSingleTransaction_WithException_ReturnsError() {
        // Given
        when(transactionRepository.saveIfPlaidTransactionNotExists(any(TransactionTable.class)))
                .thenThrow(new RuntimeException("Database error"));

        // When
        TransactionSyncHelper.SyncResult result = transactionSyncHelper.syncSingleTransaction(testTransaction, testPlaidTransactionId);

        // Then
        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getNewCount());
    }
}
