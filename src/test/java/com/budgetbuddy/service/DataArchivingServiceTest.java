package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.aws.S3Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Comprehensive tests for DataArchivingService */
class DataArchivingServiceTest {

    @Mock private TransactionRepository transactionRepository;

    @Mock private UserRepository userRepository;

    @Mock private S3Service s3Service;

    @Mock private DistributedLockService distributedLock;

    private ObjectMapper objectMapper;
    private DataArchivingService dataArchivingService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        // The scheduled wrapper acquires a distributed lock; in tests we exercise the inner
        // logic directly, so make the lock unconditionally run its work block.
        org.mockito.Mockito.lenient()
                .when(
                        distributedLock.runOnce(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.anyInt(),
                                org.mockito.ArgumentMatchers.any(Runnable.class)))
                .thenAnswer(
                        invocation -> {
                            final Runnable work = invocation.getArgument(2);
                            work.run();
                            return true;
                        });
        dataArchivingService =
                new DataArchivingService(
                        transactionRepository,
                        userRepository,
                        s3Service,
                        objectMapper,
                        distributedLock);
    }

    @Test
    @DisplayName("Should archive old transactions successfully")
    void testArchiveOldTransactionsSuccess() {
        // Given
        final String userId = "user-123";
        final List<String> activeUserIds = Arrays.asList(userId);
        when(userRepository.findActiveUserIds(365, 10_000)).thenReturn(activeUserIds);

        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setUserId(userId);
        transaction.setTransactionDate(LocalDate.now().minusDays(400).toString());

        final List<TransactionTable> oldTransactions = Arrays.asList(transaction);
        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("1970-01-01"), anyString()))
                .thenReturn(oldTransactions);

        // When
        assertDoesNotThrow(
                () -> {
                    dataArchivingService.archiveOldTransactions();
                });

        // Then
        verify(transactionRepository)
                .findByUserIdAndDateRange(anyString(), anyString(), anyString());
        verify(s3Service, atLeastOnce())
                .uploadFileInfrequentAccess(
                        anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    @DisplayName("Should handle empty user list")
    void testArchiveOldTransactionsNoUsers() {
        // Given
        when(userRepository.findActiveUserIds(365, 10_000)).thenReturn(Arrays.asList());

        // When
        assertDoesNotThrow(
                () -> {
                    dataArchivingService.archiveOldTransactions();
                });

        // Then
        verify(transactionRepository, never())
                .findByUserIdAndDateRange(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Should handle users with no old transactions")
    void testArchiveOldTransactionsNoOldTransactions() {
        // Given
        final String userId = "user-123";
        when(userRepository.findActiveUserIds(365, 10_000)).thenReturn(Arrays.asList(userId));
        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("1970-01-01"), anyString()))
                .thenReturn(Arrays.asList());

        // When
        assertDoesNotThrow(
                () -> {
                    dataArchivingService.archiveOldTransactions();
                });

        // Then
        verify(transactionRepository)
                .findByUserIdAndDateRange(anyString(), anyString(), anyString());
        verify(s3Service, never())
                .uploadFileInfrequentAccess(
                        anyString(), any(InputStream.class), anyLong(), anyString());
    }

    @Test
    @DisplayName("Should handle S3 upload failure gracefully")
    void testArchiveOldTransactionsS3Failure() {
        // Given
        final String userId = "user-123";
        when(userRepository.findActiveUserIds(365, 10_000)).thenReturn(Arrays.asList(userId));

        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setUserId(userId);

        when(transactionRepository.findByUserIdAndDateRange(
                        eq(userId), eq("1970-01-01"), anyString()))
                .thenReturn(Arrays.asList(transaction));

        doThrow(new RuntimeException("S3 error"))
                .when(s3Service)
                .uploadFileInfrequentAccess(
                        anyString(), any(InputStream.class), anyLong(), anyString());

        // When - Should not throw, should log error and continue
        assertDoesNotThrow(
                () -> {
                    dataArchivingService.archiveOldTransactions();
                });
    }
}
