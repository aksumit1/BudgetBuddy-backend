package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for CacheWarmingService */
@ExtendWith(MockitoExtension.class)
class CacheWarmingServiceTest {

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private BudgetRepository budgetRepository;

    @Mock private GoalRepository goalRepository;

    @Mock private TransactionActionRepository transactionActionRepository;

    @InjectMocks private CacheWarmingService cacheWarmingService;

    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
    }

    @Test
    void testWarmCacheForUserSuccess() {
        // Mock repository responses
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(transactionRepository.findByUserId(testUserId, 0, 50)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(transactionActionRepository.findByUserId(testUserId)).thenReturn(List.of());

        // Warm cache
        final CompletableFuture<Void> future = cacheWarmingService.warmCacheForUser(testUserId);

        // Wait for completion
        assertDoesNotThrow(() -> future.join());

        // Verify all repositories were called
        verify(accountRepository, times(1)).findByUserId(testUserId);
        verify(transactionRepository, times(1)).findByUserId(testUserId, 0, 50);
        verify(budgetRepository, times(1)).findByUserId(testUserId);
        verify(goalRepository, times(1)).findByUserId(testUserId);
        verify(transactionActionRepository, times(1)).findByUserId(testUserId);
    }

    @Test
    void testWarmCacheForUserNullUserId() {
        final CompletableFuture<Void> future = cacheWarmingService.warmCacheForUser(null);

        assertDoesNotThrow(() -> future.join());

        // Should not call any repositories
        verifyNoInteractions(accountRepository);
        verifyNoInteractions(transactionRepository);
        verifyNoInteractions(budgetRepository);
        verifyNoInteractions(goalRepository);
        verifyNoInteractions(transactionActionRepository);
    }

    @Test
    void testWarmCacheForUserEmptyUserId() {
        final CompletableFuture<Void> future = cacheWarmingService.warmCacheForUser("");

        assertDoesNotThrow(() -> future.join());

        // Should not call any repositories
        verifyNoInteractions(accountRepository);
    }

    @Test
    void testWarmCacheForUserRepositoryError() {
        // Mock repository to throw exception
        when(accountRepository.findByUserId(testUserId))
                .thenThrow(new RuntimeException("Database error"));

        // Should not throw - errors are caught and logged
        final CompletableFuture<Void> future = cacheWarmingService.warmCacheForUser(testUserId);
        assertDoesNotThrow(() -> future.join());
    }

    @Test
    void testWarmCacheForUsersMultipleUsers() {
        final List<String> userIds = List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString());

        // Mock repository responses
        userIds.forEach(
                userId -> {
                    when(accountRepository.findByUserId(userId)).thenReturn(List.of());
                    when(transactionRepository.findByUserId(userId, 0, 50)).thenReturn(List.of());
                    when(budgetRepository.findByUserId(userId)).thenReturn(List.of());
                    when(goalRepository.findByUserId(userId)).thenReturn(List.of());
                    when(transactionActionRepository.findByUserId(userId)).thenReturn(List.of());
                });

        // Warm cache for multiple users
        cacheWarmingService.warmCacheForUsers(userIds);

        // Verify all users were processed
        userIds.forEach(
                userId -> {
                    verify(accountRepository, atLeastOnce()).findByUserId(userId);
                });
    }

    @Test
    void testWarmCacheForUsersNullList() {
        assertDoesNotThrow(() -> cacheWarmingService.warmCacheForUsers(null));
        verifyNoInteractions(accountRepository);
    }

    @Test
    void testWarmCacheForUsersEmptyList() {
        assertDoesNotThrow(() -> cacheWarmingService.warmCacheForUsers(List.of()));
        verifyNoInteractions(accountRepository);
    }
}
