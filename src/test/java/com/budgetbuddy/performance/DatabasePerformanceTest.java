package com.budgetbuddy.performance;


import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.budgetbuddy.AWSTestConfiguration;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.UserService;
import com.budgetbuddy.util.TableInitializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/** Database Performance Tests Tests DynamoDB query performance, pagination, and batch operations */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
@SuppressWarnings("PMD.AvoidCatchingGenericException")
@SpringBootTest(classes = com.budgetbuddy.BudgetBuddyApplication.class)
@ActiveProfiles("test")
@Import(AWSTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabasePerformanceTest {

    @Autowired private DynamoDbClient dynamoDbClient;

    @Autowired private AccountRepository accountRepository;

    @Autowired private TransactionRepository transactionRepository;

    @Autowired private UserService userService;

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
        final String email = "perf-test-" + UUID.randomUUID() + "@example.com";
        final String passwordHash = Base64.getEncoder().encodeToString("testpassword".getBytes(StandardCharsets.UTF_8));
        testUser = userService.createUserSecure(email, passwordHash, "Performance", "Test");

        // Create test accounts
        testAccounts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            final AccountTable account = new AccountTable();
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
            final TransactionTable transaction = new TransactionTable();
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
    void testQueryPerformanceByUserId() {
        // Test query performance for findByUserId
        final int iterations = 100;
        final AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < iterations; i++) {
            final long startTime = System.nanoTime();
            transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
            final long duration = System.nanoTime() - startTime;
            totalTime.addAndGet(duration);
        }

        final double avgTimeMs = (totalTime.get() / (double) iterations) / 1_000_000;
        System.out.println("Query Performance (findByUserId):");
        System.out.println("Average time: " + String.format("%.2f", avgTimeMs) + "ms");
        System.out.println("Total iterations: " + iterations);

        // Assert reasonable performance (should be < 100ms for local DynamoDB)
        assertTrue(
                avgTimeMs < 100, "Average query time should be < 100ms, was: " + avgTimeMs + "ms");
    }

    @Test
    void testPaginationPerformance() {
        // Test pagination performance with large dataset
        final int pageSize = 10;
        final int totalPages = 10;
        final AtomicLong totalTime = new AtomicLong(0);

        for (int page = 0; page < totalPages; page++) {
            final long startTime = System.nanoTime();
            transactionRepository.findByUserId(testUser.getUserId(), page * pageSize, pageSize);
            final long duration = System.nanoTime() - startTime;
            totalTime.addAndGet(duration);
        }

        final double avgTimeMs = (totalTime.get() / (double) totalPages) / 1_000_000;
        System.out.println("Pagination Performance:");
        System.out.println("Average time per page: " + String.format("%.2f", avgTimeMs) + "ms");
        System.out.println("Total pages: " + totalPages);

        assertTrue(
                avgTimeMs < 100,
                "Average pagination time should be < 100ms, was: " + avgTimeMs + "ms");
    }

    @Test
    void testDateRangeQueryPerformance() {
        // Test date range query performance
        final String startDate = LocalDate.now().minusDays(30).toString();
        final String endDate = LocalDate.now().toString();
        final int iterations = 50;
        final AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < iterations; i++) {
            final long startTime = System.nanoTime();
            transactionRepository.findByUserIdAndDateRange(
                    testUser.getUserId(), startDate, endDate);
            final long duration = System.nanoTime() - startTime;
            totalTime.addAndGet(duration);
        }

        final double avgTimeMs = (totalTime.get() / (double) iterations) / 1_000_000;
        System.out.println("Date Range Query Performance:");
        System.out.println("Average time: " + String.format("%.2f", avgTimeMs) + "ms");
        System.out.println("Total iterations: " + iterations);

        assertTrue(
                avgTimeMs < 150,
                "Average date range query time should be < 150ms, was: " + avgTimeMs + "ms");
    }

    @Test
    void testConcurrentQueries() throws InterruptedException {
        // Test concurrent query performance
        final int concurrentThreads = 20;
        final int queriesPerThread = 10;
        final ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        final CountDownLatch latch = new CountDownLatch(concurrentThreads);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < concurrentThreads; i++) {
            executor.submit(
                    () -> {
                        try {
                            for (int j = 0; j < queriesPerThread; j++) {
                                final long startTime = System.nanoTime();
                                transactionRepository.findByUserId(testUser.getUserId(), 0, 100);
                                final long duration = System.nanoTime() - startTime;
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

        final int totalQueries = concurrentThreads * queriesPerThread;
        final double avgTimeMs = (totalTime.get() / (double) totalQueries) / 1_000_000;

        System.out.println("Concurrent Query Performance:");
        System.out.println("Concurrent threads: " + concurrentThreads);
        System.out.println("Queries per thread: " + queriesPerThread);
        System.out.println("Total queries: " + totalQueries);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Average time: " + String.format("%.2f", avgTimeMs) + "ms");

        assertTrue(successCount.get() > 0, "Should have successful queries");
        assertTrue(
                avgTimeMs < 200,
                "Average concurrent query time should be < 200ms, was: " + avgTimeMs + "ms");
    }

    @Test
    void testAccountRepositoryFindByUserId() {
        // Test account repository query performance
        final int iterations = 100;
        final AtomicLong totalTime = new AtomicLong(0);

        for (int i = 0; i < iterations; i++) {
            final long startTime = System.nanoTime();
            accountRepository.findByUserId(testUser.getUserId());
            final long duration = System.nanoTime() - startTime;
            totalTime.addAndGet(duration);
        }

        final double avgTimeMs = (totalTime.get() / (double) iterations) / 1_000_000;
        System.out.println("Account Query Performance (findByUserId):");
        System.out.println("Average time: " + String.format("%.2f", avgTimeMs) + "ms");

        assertTrue(
                avgTimeMs < 100,
                "Average account query time should be < 100ms, was: " + avgTimeMs + "ms");
    }
}
