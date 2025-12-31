package com.budgetbuddy.performance;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Database Performance Tests
 * Tests DynamoDB query performance, pagination, and batch operations
 */
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabasePerformanceTest {

    @Autowired
    private DynamoDbClient dynamoDbClient;


    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private UserService userService;

    private UserTable testUser;
    private List<AccountTable> testAccounts;
    private List<TransactionTable> testTransactions;

    @BeforeAll
    void ensureTablesInitialized() {
        TableInitializer.ensureTablesInitializedAndVerified(dynamoDbClient);
    }

    @BeforeEach
    void setUp() {
        // Create test user
        String email = "perf-test-" + UUID.randomUUID() + "@example.com";
        String passwordHash = Base64.getEncoder().encodeToString("testpassword".getBytes());
        testUser = userService.createUserSecure(email, passwordHash, "Performance", "Test");

        // Create test accounts
        testAccounts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            AccountTable account = new AccountTable();
            account.setAccountId(UUID.randomUUID().toString());
            account.setUserId(testUser.getUserId());
            account.setAccountName("Test Account " + i);
            account.setInstitutionName("Test Bank");
            account.setAccountType("CHECKING");
            account.setBalance(new BigDecimal("1000.00"));
            accountRepository.save(account);
            testAccounts.add(account);
        }

        // Create test transactions
        testTransactions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            TransactionTable transaction = new TransactionTable();
            transaction.setTransactionId(UUID.randomUUID().toString());
            transaction.setUserId(testUser.getUserId());
            transaction.setAccountId(testAccounts.get(i % 10).getAccountId());
            transaction.setAmount(new BigDecimal("-" + (i + 1) + ".00"));
            transaction.setTransactionDate(LocalDate.now().minusDays(i % 30).toString());
            transaction.setDescription("Test Transaction " + i);
            transactionRepository.save(transaction);
            testTransactions.add(transaction);
        }
    }

    @Test
    void testQueryPerformance_ByUserId() {
        // Test query performance for findByUserId
        int iterations = 100;
        AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < iterations; i++) {
            long startTime = System.nanoTime();
            transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
            long duration = System.nanoTime() - startTime;
            totalTime.addAndGet(duration);
        }

        double avgTimeMs = (totalTime.get() / (double) iterations) / 1_000_000;
        System.out.println("Query Performance (findByUserId):");
        System.out.println("Average time: " + String.format("%.2f", avgTimeMs) + "ms");
        System.out.println("Total iterations: " + iterations);

        // Assert reasonable performance (should be < 100ms for local DynamoDB)
        assertTrue(avgTimeMs < 100, "Average query time should be < 100ms, was: " + avgTimeMs + "ms");
    }

    @Test
    void testPaginationPerformance() {
        // Test pagination performance with large dataset
        int pageSize = 10;
        int totalPages = 10;
        AtomicLong totalTime = new AtomicLong(0);

        for (int page = 0; page < totalPages; page++) {
            long startTime = System.nanoTime();
            transactionRepository.findByUserId(testUser.getUserId(), page * pageSize, pageSize);
            long duration = System.nanoTime() - startTime;
            totalTime.addAndGet(duration);
        }

        double avgTimeMs = (totalTime.get() / (double) totalPages) / 1_000_000;
        System.out.println("Pagination Performance:");
        System.out.println("Average time per page: " + String.format("%.2f", avgTimeMs) + "ms");
        System.out.println("Total pages: " + totalPages);

        assertTrue(avgTimeMs < 100, "Average pagination time should be < 100ms, was: " + avgTimeMs + "ms");
    }

    @Test
    void testDateRangeQueryPerformance() {
        // Test date range query performance
        String startDate = LocalDate.now().minusDays(30).toString();
        String endDate = LocalDate.now().toString();
        int iterations = 50;
        AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < iterations; i++) {
            long startTime = System.nanoTime();
            transactionRepository.findByUserIdAndDateRange(testUser.getUserId(), startDate, endDate);
            long duration = System.nanoTime() - startTime;
            totalTime.addAndGet(duration);
        }

        double avgTimeMs = (totalTime.get() / (double) iterations) / 1_000_000;
        System.out.println("Date Range Query Performance:");
        System.out.println("Average time: " + String.format("%.2f", avgTimeMs) + "ms");
        System.out.println("Total iterations: " + iterations);

        assertTrue(avgTimeMs < 150, "Average date range query time should be < 150ms, was: " + avgTimeMs + "ms");
    }

    @Test
    void testConcurrentQueries() throws InterruptedException {
        // Test concurrent query performance
        int concurrentThreads = 20;
        int queriesPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch latch = new CountDownLatch(concurrentThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < concurrentThreads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < queriesPerThread; j++) {
                        long startTime = System.nanoTime();
                        transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
                        long duration = System.nanoTime() - startTime;
                        totalTime.addAndGet(duration);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // Ignore exceptions
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        int totalQueries = concurrentThreads * queriesPerThread;
        double avgTimeMs = (totalTime.get() / (double) totalQueries) / 1_000_000;
        
        System.out.println("Concurrent Query Performance:");
        System.out.println("Concurrent threads: " + concurrentThreads);
        System.out.println("Queries per thread: " + queriesPerThread);
        System.out.println("Total queries: " + totalQueries);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Average time: " + String.format("%.2f", avgTimeMs) + "ms");

        assertTrue(successCount.get() > 0, "Should have successful queries");
        assertTrue(avgTimeMs < 200, "Average concurrent query time should be < 200ms, was: " + avgTimeMs + "ms");
    }

    @Test
    void testAccountRepositoryFindByUserId() {
        // Test account repository query performance
        int iterations = 100;
        AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < iterations; i++) {
            long startTime = System.nanoTime();
            accountRepository.findByUserId(testUser.getUserId());
            long duration = System.nanoTime() - startTime;
            totalTime.addAndGet(duration);
        }

        double avgTimeMs = (totalTime.get() / (double) iterations) / 1_000_000;
        System.out.println("Account Query Performance (findByUserId):");
        System.out.println("Average time: " + String.format("%.2f", avgTimeMs) + "ms");

        assertTrue(avgTimeMs < 100, "Average account query time should be < 100ms, was: " + avgTimeMs + "ms");
    }
}

