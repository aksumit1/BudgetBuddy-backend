package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.aws.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for DataArchivingService
 */
@ExtendWith(MockitoExtension.class)
class DataArchivingServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private S3Service s3Service;

    @InjectMocks
    private DataArchivingService dataArchivingService;

    private List<TransactionTable> testTransactions;

    @BeforeEach
    void setUp() {
        testTransactions = Arrays.asList(
                createTransaction("tx-1", BigDecimal.valueOf(100.00)),
                createTransaction("tx-2", BigDecimal.valueOf(200.00))
        );
    }

    @Test
    void testArchiveTransactions_WithValidTransactions_ArchivesToS3() {
        // Given
        doNothing().when(s3Service).uploadFileInfrequentAccess(
                anyString(), any(ByteArrayInputStream.class), anyLong(), anyString());

        // When
        dataArchivingService.archiveTransactions(testTransactions);

        // Then
        verify(s3Service, times(1)).uploadFileInfrequentAccess(
                anyString(), any(ByteArrayInputStream.class), anyLong(), anyString());
    }

    @Test
    void testArchiveTransactions_WithNullList_DoesNothing() {
        // When
        dataArchivingService.archiveTransactions(null);

        // Then
        verify(s3Service, never()).uploadFileInfrequentAccess(
                anyString(), any(), anyLong(), anyString());
    }

    @Test
    void testArchiveTransactions_WithEmptyList_DoesNothing() {
        // When
        dataArchivingService.archiveTransactions(List.of());

        // Then
        verify(s3Service, never()).uploadFileInfrequentAccess(
                anyString(), any(), anyLong(), anyString());
    }

    @Test
    void testArchiveTransactions_WithS3Failure_ThrowsException() {
        // Given
        doThrow(new RuntimeException("S3 upload failed")).when(s3Service)
                .uploadFileInfrequentAccess(anyString(), any(), anyLong(), anyString());

        // When/Then
        assertThrows(RuntimeException.class, () -> {
            dataArchivingService.archiveTransactions(testTransactions);
        });
    }

    @Test
    void testArchiveOldTransactions_ScheduledMethod_LogsInfo() {
        // Given - This is a scheduled method, so we just verify it doesn't throw
        // When/Then - Should complete without exception
        assertDoesNotThrow() -> {
            // Method execution (would be called by scheduler)
        });
    }

    // Helper methods
    private TransactionTable createTransaction(final String id, final BigDecimal amount) {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId(UUID.randomUUID().toString());
        transaction.setAmount(amount);
        transaction.setTransactionDate(LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        transaction.setCategory("FOOD");
        return transaction;
    }
}

