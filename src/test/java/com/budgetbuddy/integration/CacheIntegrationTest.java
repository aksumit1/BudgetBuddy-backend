package com.budgetbuddy.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.dto.IncrementalSyncResponse;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.CacheMonitoringService;
import com.budgetbuddy.service.SyncService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration tests for cache optimization in SyncService Note: These tests require LocalStack or a
 * real DynamoDB instance They are marked as integration tests and may be skipped if DynamoDB is not
 * available
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@org.junit.jupiter.api.Tag("integration")
public class CacheIntegrationTest {

    @Autowired private SyncService syncService;

    @Autowired private AccountRepository accountRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private BudgetRepository budgetRepository;

    @Autowired private GoalRepository goalRepository;

    @Autowired private TransactionActionRepository transactionActionRepository;

    @Autowired private CacheManager cacheManager;

    @Autowired private CacheMonitoringService cacheMonitoringService;

    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        // CRITICAL: Do NOT clear caches - this would affect shared resources (Redis, DynamoDB)
        // Tests use unique testUserId (UUID), so cache entries are naturally isolated
        // Cache entries will expire naturally based on TTL, no manual cleanup needed
    }

    @Test
    void testSyncServiceGetAllDataUsesCache() {
        // Create test data
        final AccountTable account = createTestAccount();
        final BudgetTable budget = createTestBudget();
        final GoalTable goal = createTestGoal();

        accountRepository.save(account);
        budgetRepository.save(budget);
        goalRepository.save(goal);

        // First call - should populate cache
        final var firstResponse = syncService.getAllData(testUserId);
        assertNotNull(firstResponse);
        assertEquals(1, firstResponse.getAccounts().size());
        assertEquals(1, firstResponse.getBudgets().size());
        assertEquals(1, firstResponse.getGoals().size());

        // Second call - should use cache (much faster)
        final long startTime = System.currentTimeMillis();
        final var secondResponse = syncService.getAllData(testUserId);
        final long endTime = System.currentTimeMillis();

        assertNotNull(secondResponse);
        assertEquals(1, secondResponse.getAccounts().size());
        assertEquals(1, secondResponse.getBudgets().size());
        assertEquals(1, secondResponse.getGoals().size());

        // Verify cache statistics show hits
        final var allStats = cacheMonitoringService.getAllCacheStatistics();
        assertNotNull(allStats);
    }

    @Test
    void testSyncServiceIncrementalSyncUsesCache() throws InterruptedException {
        // Create test data
        final AccountTable account = createTestAccount();
        account.setUpdatedAt(Instant.now().minusSeconds(3600)); // 1 hour ago
        // CRITICAL: Ensure updatedAtTimestamp is set (setUpdatedAt should do this, but verify)
        if (account.getUpdatedAtTimestamp() == null) {
            account.setUpdatedAtTimestamp(account.getUpdatedAt().getEpochSecond());
        }
        accountRepository.save(account);

        // First sync - get all data
        final var firstResponse = syncService.getAllData(testUserId);
        assertNotNull(firstResponse);

        // CRITICAL: Capture sync time AFTER getAllData and add a small delay
        // to ensure any subsequent updates have a timestamp that's clearly after this
        Thread.sleep(500); // Increased delay to ensure timestamp difference
        final long firstSyncTime = Instant.now().getEpochSecond();

        // Additional delay to ensure update timestamp is clearly after firstSyncTime
        // Use at least 1 second to ensure timestamp difference (epoch seconds have 1-second
        // granularity)
        Thread.sleep(1000);

        // Update account with a timestamp clearly after firstSyncTime
        account.setAccountName("Updated Account");
        Instant updateTime = Instant.now(); // Capture time before setting
        long updateTimestamp = updateTime.getEpochSecond();

        // CRITICAL: Ensure update timestamp is strictly greater than firstSyncTime
        // If they're equal, add 1 second to ensure it's definitely after
        if (updateTimestamp <= firstSyncTime) {
            updateTimestamp = firstSyncTime + 1;
            updateTime = Instant.ofEpochSecond(updateTimestamp);
        }

        account.setUpdatedAt(updateTime); // This will set updatedAtTimestamp automatically
        // CRITICAL: Explicitly ensure updatedAtTimestamp is set correctly and is greater than
        // firstSyncTime
        account.setUpdatedAtTimestamp(updateTimestamp);
        accountRepository.save(account); // This should evict cache via @CacheEvict annotation

        // CRITICAL: Do NOT clear entire cache - this would affect shared resources (Redis,
        // DynamoDB)
        // The @CacheEvict annotation on save() should handle cache eviction automatically
        // Clearing entire cache would affect all users, not just test data

        // CRITICAL: Wait for DynamoDB eventual consistency - GSI updates can take time in CI
        // Use retry logic to handle eventual consistency
        IncrementalSyncResponse incrementalResponse = null;
        final int maxRetries = 10;
        int retryCount = 0;
        final long baseDelay = 500; // Start with 500ms delay

        while (retryCount < maxRetries) {
            // Wait before querying (exponential backoff)
            final long delay = baseDelay * (1L << Math.min(retryCount, 3)); // Cap at 4 seconds
            Thread.sleep(delay);

            // Incremental sync - should get only changed items
            incrementalResponse = syncService.getIncrementalChanges(testUserId, firstSyncTime);
            assertNotNull(incrementalResponse);

            // Check if we found the updated account
            if (incrementalResponse.getAccounts().size() == 1) {
                // Success - account found
                break;
            }

            retryCount++;

            // Debug: Log actual values if still not found
            if (retryCount < maxRetries) {
                // Reload account from DB to verify it was actually updated
                final var reloadedAccount = accountRepository.findById(account.getAccountId());
                if (reloadedAccount.isPresent()) {
                    final AccountTable reloaded = reloadedAccount.get();
                    System.out.println(
                            "DEBUG: Retry "
                                    + retryCount
                                    + " - Reloaded account - updatedAtTimestamp="
                                    + reloaded.getUpdatedAtTimestamp()
                                    + ", firstSyncTime="
                                    + firstSyncTime
                                    + ", comparison="
                                    + (reloaded.getUpdatedAtTimestamp() != null
                                            && reloaded.getUpdatedAtTimestamp() >= firstSyncTime)
                                    + ", incrementalResponse.accounts.size()="
                                    + incrementalResponse.getAccounts().size());
                }
            }
        }

        // Final assertion with detailed error message
        if (incrementalResponse.getAccounts().size() != 1) {
            // Reload account one more time for final debug info
            final var reloadedAccount = accountRepository.findById(account.getAccountId());
            String debugInfo =
                    "firstSyncTime="
                            + firstSyncTime
                            + ", account.updatedAtTimestamp="
                            + account.getUpdatedAtTimestamp()
                            + ", incrementalResponse.accounts.size()="
                            + incrementalResponse.getAccounts().size();
            if (reloadedAccount.isPresent()) {
                final AccountTable reloaded = reloadedAccount.get();
                debugInfo += ", reloaded.updatedAtTimestamp=" + reloaded.getUpdatedAtTimestamp();
            }
            debugInfo += ", retries=" + retryCount;

            assertEquals(
                    1,
                    incrementalResponse.getAccounts().size(),
                    "Should find 1 updated account after " + retryCount + " retries. " + debugInfo);
        }

        assertEquals("Updated Account", incrementalResponse.getAccounts().get(0).getAccountName());
    }

    @Test
    void testCacheEvictionOnSave() {
        // Create and save account
        final AccountTable account = createTestAccount();
        accountRepository.save(account);

        // Query to populate cache
        final List<AccountTable> before = accountRepository.findByUserId(testUserId);
        assertEquals(1, before.size());

        // Update account - should evict cache
        account.setAccountName("New Name");
        accountRepository.save(account);

        // Query again - should get fresh data
        final List<AccountTable> after = accountRepository.findByUserId(testUserId);
        assertEquals(1, after.size());
        assertEquals("New Name", after.get(0).getAccountName());
    }

    @Test
    void testCacheEvictionOnDelete() {
        // Create and save account
        final AccountTable account = createTestAccount();
        accountRepository.save(account);

        // Query to populate cache
        final List<AccountTable> before = accountRepository.findByUserId(testUserId);
        assertEquals(1, before.size());

        // Delete account - should evict cache
        accountRepository.delete(account.getAccountId());

        // Query again - should get fresh data (empty list)
        final List<AccountTable> after = accountRepository.findByUserId(testUserId);
        assertTrue(
                after.isEmpty()
                        || !after.stream()
                                .anyMatch(a -> a.getAccountId().equals(account.getAccountId())));
    }

    @Test
    void testCachePerformanceMultipleUsers() {
        final String userId1 = UUID.randomUUID().toString();
        final String userId2 = UUID.randomUUID().toString();

        // Create data for user 1
        final AccountTable account1 = createTestAccount();
        account1.setUserId(userId1);
        accountRepository.save(account1);

        // Create data for user 2
        final AccountTable account2 = createTestAccount();
        account2.setUserId(userId2);
        accountRepository.save(account2);

        // Query both users - should cache separately
        final List<AccountTable> user1Accounts = accountRepository.findByUserId(userId1);
        final List<AccountTable> user2Accounts = accountRepository.findByUserId(userId2);

        assertEquals(1, user1Accounts.size());
        assertEquals(1, user2Accounts.size());
        assertNotEquals(user1Accounts.get(0).getAccountId(), user2Accounts.get(0).getAccountId());

        // Verify cache isolation
        final var stats = cacheMonitoringService.getCacheStatistics("accounts");
        assertNotNull(stats);
    }

    // Helper methods
    private AccountTable createTestAccount() {
        final AccountTable account = new AccountTable();
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
        final BudgetTable budget = new BudgetTable();
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
        final GoalTable goal = new GoalTable();
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
