package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.aws.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DataArchivingService
 * Tests transaction archiving functionality
 */
@ExtendWith(MockitoExtension.class)
class DataArchivingServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private S3Service s3Service;

    private DataArchivingService dataArchivingService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        // Create real ObjectMapper for tests (same as Spring config)
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        
        dataArchivingService = new DataArchivingService(transactionRepository, userRepository, s3Service, objectMapper);
    }

    @Test
    void archiveTransactions_WithValidTransactions_ArchivesSuccessfully() {
        // Given - List of transactions to archive
        TransactionTable transaction1 = new TransactionTable();
        transaction1.setTransactionId("txn-1");
        transaction1.setUserId("user-1");
        transaction1.setAmount(java.math.BigDecimal.valueOf(100.0));
        transaction1.setUpdatedAt(Instant.now());

        TransactionTable transaction2 = new TransactionTable();
        transaction2.setTransactionId("txn-2");
        transaction2.setUserId("user-1");
        transaction2.setAmount(java.math.BigDecimal.valueOf(200.0));
        transaction2.setUpdatedAt(Instant.now());

        List<TransactionTable> transactions = Arrays.asList(transaction1, transaction2);

        // Mock S3 service to succeed (returns String URL)
        when(s3Service.uploadFileInfrequentAccess(
                anyString(),
                any(ByteArrayInputStream.class),
                anyLong(),
                anyString()
        )).thenReturn("s3://bucket/archive/transactions/2024-01-01.gz");

        // When - Archive transactions
        assertDoesNotThrow(() -> {
            dataArchivingService.archiveTransactions(transactions);
        });

        // Then - Should upload to S3
        verify(s3Service, times(1)).uploadFileInfrequentAccess(
                anyString(),
                any(ByteArrayInputStream.class),
                anyLong(),
                eq("application/gzip")
        );
    }

    @Test
    void archiveTransactions_WithNullList_HandlesGracefully() {
        // When - Archive null list
        assertDoesNotThrow(() -> {
            dataArchivingService.archiveTransactions(null);
        });

        // Then - Should not call S3 service
        verify(s3Service, never()).uploadFileInfrequentAccess(
                anyString(),
                any(),
                anyLong(),
                anyString()
        );
    }

    @Test
    void archiveTransactions_WithEmptyList_HandlesGracefully() {
        // When - Archive empty list
        assertDoesNotThrow(() -> {
            dataArchivingService.archiveTransactions(List.of());
        });

        // Then - Should not call S3 service
        verify(s3Service, never()).uploadFileInfrequentAccess(
                anyString(),
                any(),
                anyLong(),
                anyString()
        );
    }

    @Test
    void archiveTransactions_WithS3Error_ThrowsException() {
        // Given - Transactions and S3 service that throws exception
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-1");
        transaction.setUserId("user-1");
        transaction.setAmount(java.math.BigDecimal.valueOf(100.0));
        transaction.setUpdatedAt(Instant.now());

        List<TransactionTable> transactions = List.of(transaction);

        // Mock S3 service to throw exception during upload
        when(s3Service.uploadFileInfrequentAccess(
                anyString(),
                any(ByteArrayInputStream.class),
                anyLong(),
                anyString()
        )).thenThrow(new RuntimeException("S3 upload failed"));

        // When/Then - Should throw RuntimeException (wrapped from S3 error)
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            dataArchivingService.archiveTransactions(transactions);
        });
        
        // Verify the exception message indicates archiving failure
        assertTrue(exception.getMessage().contains("Failed to archive transactions"), 
                   "Exception should indicate archiving failure");
        
        // Verify S3 service was called (compression succeeded, but upload failed)
        verify(s3Service, times(1)).uploadFileInfrequentAccess(
                anyString(),
                any(ByteArrayInputStream.class),
                anyLong(),
                anyString()
        );
    }

    @Test
    void archiveOldTransactions_ScheduledJob_CompletesWithoutError() {
        // When - Scheduled job runs (simulated by calling directly)
        assertDoesNotThrow(() -> {
            dataArchivingService.archiveOldTransactions();
        });

        // Then - Should complete without errors
        // Note: Current implementation logs info but doesn't perform actual archiving
        // This is expected behavior until per-user archiving is implemented
    }
}
