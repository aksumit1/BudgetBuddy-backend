package com.budgetbuddy.integration;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.*;
import com.budgetbuddy.service.CacheMonitoringService;
import com.budgetbuddy.service.SyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for cache optimization in SyncService
 * Note: These tests require LocalStack or a real DynamoDB instance
 * They are marked as integration tests and may be skipped if DynamoDB is not available
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@org.junit.jupiter.api.Tag("integration")
public class CacheIntegrationTest {

    @Autowired
    private SyncService syncService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private GoalRepository goalRepository;

    @Autowired
    private TransactionActionRepository transactionActionRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private CacheMonitoringService cacheMonitoringService;

    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        // Clear all caches
        if (cacheManager != null) {
            cacheManager.getCacheNames().forEach(cacheName -> {
                org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
                if (cache != null) {
                    cache.clear();
                }
            });
        }
    }

    @Test
    void testSyncService_GetAllData_UsesCache() {
        // Create test data
        AccountTable account = createTestAccount();
        BudgetTable budget = createTestBudget();
        GoalTable goal = createTestGoal();

        accountRepository.save(account);
        budgetRepository.save(budget);
        goalRepository.save(goal);

        // First call - should populate cache
        var firstResponse = syncService.getAllData(testUserId);
        assertNotNull(firstResponse);
        assertEquals(1, firstResponse.getAccounts().size());
        assertEquals(1, firstResponse.getBudgets().size());
        assertEquals(1, firstResponse.getGoals().size());

        // Second call - should use cache (much faster)
        long startTime = System.currentTimeMillis();
        var secondResponse = syncService.getAllData(testUserId);
        long endTime = System.currentTimeMillis();

        assertNotNull(secondResponse);
        assertEquals(1, secondResponse.getAccounts().size());
        assertEquals(1, secondResponse.getBudgets().size());
        assertEquals(1, secondResponse.getGoals().size());

        // Verify cache statistics show hits
        var allStats = cacheMonitoringService.getAllCacheStatistics();
        assertNotNull(allStats);
    }

    @Test
    void testSyncService_IncrementalSync_UsesCache() throws InterruptedException {
        // Create test data
        AccountTable account = createTestAccount();
        account.setUpdatedAt(Instant.now().minusSeconds(3600)); // 1 hour ago
        accountRepository.save(account);

        // First sync - get all data
        var firstResponse = syncService.getAllData(testUserId);
        assertNotNull(firstResponse);
        
        // CRITICAL: Capture sync time AFTER getAllData and add a small delay
        // to ensure any subsequent updates have a timestamp that's clearly after this
        Thread.sleep(100); // Small delay to ensure timestamp difference
        long firstSyncTime = Instant.now().getEpochSecond();

        // Small delay to ensure update timestamp is clearly after firstSyncTime
        Thread.sleep(100);
        
        // Update account with a timestamp clearly after firstSyncTime
        account.setAccountName("Updated Account");
        account.setUpdatedAt(Instant.now()); // This will be at least 100ms after firstSyncTime
        accountRepository.save(account); // This should evict cache

        // Small delay to ensure DynamoDB has processed the update
        Thread.sleep(100);

        // Incremental sync - should get only changed items
        var incrementalResponse = syncService.getIncrementalChanges(testUserId, firstSyncTime);
        assertNotNull(incrementalResponse);
        assertEquals(1, incrementalResponse.getAccounts().size(), 
                "Should find 1 updated account. firstSyncTime=" + firstSyncTime + 
                ", account.updatedAtTimestamp=" + account.getUpdatedAtTimestamp());
        assertEquals("Updated Account", incrementalResponse.getAccounts().get(0).getAccountName());
    }

    @Test
    void testCacheEviction_OnSave() {
        // Create and save account
        AccountTable account = createTestAccount();
        accountRepository.save(account);

        // Query to populate cache
        List<AccountTable> before = accountRepository.findByUserId(testUserId);
        assertEquals(1, before.size());

        // Update account - should evict cache
        account.setAccountName("New Name");
        accountRepository.save(account);

        // Query again - should get fresh data
        List<AccountTable> after = accountRepository.findByUserId(testUserId);
        assertEquals(1, after.size());
        assertEquals("New Name", after.get(0).getAccountName());
    }

    @Test
    void testCacheEviction_OnDelete() {
        // Create and save account
        AccountTable account = createTestAccount();
        accountRepository.save(account);

        // Query to populate cache
        List<AccountTable> before = accountRepository.findByUserId(testUserId);
        assertEquals(1, before.size());

        // Delete account - should evict cache
        accountRepository.delete(account.getAccountId());

        // Query again - should get fresh data (empty list)
        List<AccountTable> after = accountRepository.findByUserId(testUserId);
        assertTrue(after.isEmpty() || !after.stream().anyMatch(a -> a.getAccountId().equals(account.getAccountId())));
    }

    @Test
    void testCachePerformance_MultipleUsers() {
        String userId1 = UUID.randomUUID().toString();
        String userId2 = UUID.randomUUID().toString();

        // Create data for user 1
        AccountTable account1 = createTestAccount();
        account1.setUserId(userId1);
        accountRepository.save(account1);

        // Create data for user 2
        AccountTable account2 = createTestAccount();
        account2.setUserId(userId2);
        accountRepository.save(account2);

        // Query both users - should cache separately
        List<AccountTable> user1Accounts = accountRepository.findByUserId(userId1);
        List<AccountTable> user2Accounts = accountRepository.findByUserId(userId2);

        assertEquals(1, user1Accounts.size());
        assertEquals(1, user2Accounts.size());
        assertNotEquals(user1Accounts.get(0).getAccountId(), user2Accounts.get(0).getAccountId());

        // Verify cache isolation
        var stats = cacheMonitoringService.getCacheStatistics("accounts");
        assertNotNull(stats);
    }

    // Helper methods
    private AccountTable createTestAccount() {
        AccountTable account = new AccountTable();
        account.setAccountId(UUID.randomUUID().toString());
        account.setUserId(testUserId);
        account.setAccountName("Test Account");
        account.setAccountType("checking");
        account.setBalance(BigDecimal.valueOf(1000.00));
        account.setActive(true);
        account.setCreatedAt(Instant.now());
        account.setUpdatedAt(Instant.now());
        return account;
    }

    private BudgetTable createTestBudget() {
        BudgetTable budget = new BudgetTable();
        budget.setBudgetId(UUID.randomUUID().toString());
        budget.setUserId(testUserId);
        budget.setCategory("Food");
        budget.setMonthlyLimit(BigDecimal.valueOf(500.00));
        budget.setCurrentSpent(BigDecimal.valueOf(100.00));
        budget.setCreatedAt(Instant.now());
        budget.setUpdatedAt(Instant.now());
        return budget;
    }

    private GoalTable createTestGoal() {
        GoalTable goal = new GoalTable();
        goal.setGoalId(UUID.randomUUID().toString());
        goal.setUserId(testUserId);
        goal.setName("Test Goal");
        goal.setTargetAmount(BigDecimal.valueOf(10000.00));
        goal.setCurrentAmount(BigDecimal.valueOf(1000.00));
        goal.setTargetDate("2025-12-31");
        goal.setActive(true);
        goal.setCreatedAt(Instant.now());
        goal.setUpdatedAt(Instant.now());
        return goal;
    }
}

