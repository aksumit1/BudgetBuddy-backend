package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.aws.S3Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DataArchivingService
 */
class DataArchivingServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private S3Service s3Service;

    private ObjectMapper objectMapper;
    private DataArchivingService dataArchivingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        dataArchivingService = new DataArchivingService(
                transactionRepository, userRepository, s3Service, objectMapper);
    }

    @Test
    @DisplayName("Should archive old transactions successfully")
    void testArchiveOldTransactions_Success() {
        // Given
        String userId = "user-123";
        List<String> activeUserIds = Arrays.asList(userId);
        when(userRepository.findActiveUserIds(365, 10000)).thenReturn(activeUserIds);

        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setUserId(userId);
        transaction.setTransactionDate(LocalDate.now().minusDays(400).toString());

        List<TransactionTable> oldTransactions = Arrays.asList(transaction);
        when(transactionRepository.findByUserIdAndDateRange(
                eq(userId), eq("1970-01-01"), anyString()))
                .thenReturn(oldTransactions);

        // When
        assertDoesNotThrow(() -> {
            dataArchivingService.archiveOldTransactions();
        });

        // Then
        verify(transactionRepository).findByUserIdAndDateRange(anyString(), anyString(), anyString());
        verify(s3Service, atLeastOnce()).uploadFileInfrequentAccess(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    @DisplayName("Should handle empty user list")
    void testArchiveOldTransactions_NoUsers() {
        // Given
        when(userRepository.findActiveUserIds(365, 10000)).thenReturn(Arrays.asList());

        // When
        assertDoesNotThrow(() -> {
            dataArchivingService.archiveOldTransactions();
        });

        // Then
        verify(transactionRepository, never()).findByUserIdAndDateRange(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should handle users with no old transactions")
    void testArchiveOldTransactions_NoOldTransactions() {
        // Given
        String userId = "user-123";
        when(userRepository.findActiveUserIds(365, 10000)).thenReturn(Arrays.asList(userId));
        when(transactionRepository.findByUserIdAndDateRange(
                eq(userId), eq("1970-01-01"), anyString()))
                .thenReturn(Arrays.asList());

        // When
        assertDoesNotThrow(() -> {
            dataArchivingService.archiveOldTransactions();
        });

        // Then
        verify(transactionRepository).findByUserIdAndDateRange(anyString(), anyString(), anyString());
        verify(s3Service, never()).uploadFileInfrequentAccess(anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    @DisplayName("Should handle S3 upload failure gracefully")
    void testArchiveOldTransactions_S3Failure() {
        // Given
        String userId = "user-123";
        when(userRepository.findActiveUserIds(365, 10000)).thenReturn(Arrays.asList(userId));

        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setUserId(userId);

        when(transactionRepository.findByUserIdAndDateRange(
                eq(userId), eq("1970-01-01"), anyString()))
                .thenReturn(Arrays.asList(transaction));

        doThrow(new RuntimeException("S3 error")).when(s3Service)
                .uploadFileInfrequentAccess(anyString(), any(InputStream.class), anyLong(), anyString());

        // When - Should not throw, should log error and continue
        assertDoesNotThrow(() -> {
            dataArchivingService.archiveOldTransactions();
        });
    }
}
