package com.budgetbuddy.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.aws.CloudWatchService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for AnalyticsService */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AnalyticsServiceTest {

    @Mock private TransactionRepository transactionRepository;

    @Mock private TransactionService transactionService;

    @Mock private CloudWatchService cloudWatchService;

    @InjectMocks private AnalyticsService analyticsService;

    private UserTable testUser;
    private LocalDate startDate;
    private LocalDate endDate;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId(UUID.randomUUID().toString());
        testUser.setEmail("test@example.com");

        startDate = LocalDate.now().minusDays(30);
        endDate = LocalDate.now();
    }

    @Test
    void testGetSpendingSummaryWithValidInputReturnsSummary() {
        // Given
        final BigDecimal totalSpending = BigDecimal.valueOf(1000.00);
        final List<TransactionTable> transactions =
                Arrays.asList(createTransaction("tx-1", BigDecimal.valueOf(500.00)));

        when(transactionService.getTotalSpending(testUser, startDate, endDate))
                .thenReturn(totalSpending);
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate))
                .thenReturn(transactions);
        doNothing().when(cloudWatchService).putMetric(anyString(), anyDouble(), anyString());

        // When
        final AnalyticsService.SpendingSummary summary =
                analyticsService.getSpendingSummary(testUser, startDate, endDate);

        // Then
        assertNotNull(summary);
        assertEquals(totalSpending, summary.getTotalSpending());
        assertEquals(1, summary.getTransactionCount());
        verify(cloudWatchService, times(2)).putMetric(anyString(), anyDouble(), anyString());
    }

    @Test
    void testGetSpendingSummaryWithNullTotalSpendingReturnsZero() {
        // Given
        when(transactionService.getTotalSpending(testUser, startDate, endDate)).thenReturn(null);
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate))
                .thenReturn(List.of());

        // When
        final AnalyticsService.SpendingSummary summary =
                analyticsService.getSpendingSummary(testUser, startDate, endDate);

        // Then
        assertNotNull(summary);
        assertEquals(BigDecimal.ZERO, summary.getTotalSpending());
    }

    @Test
    void testGetSpendingByCategoryWithValidInputReturnsCategoryMap() {
        // Given
        final List<TransactionTable> transactions =
                Arrays.asList(
                        createTransaction("tx-1", "FOOD", BigDecimal.valueOf(100.00)),
                        createTransaction("tx-3", "TRANSPORTATION", BigDecimal.valueOf(75.00)));

        when(transactionService.getTransactionsInRange(testUser, startDate, endDate))
                .thenReturn(transactions);

        // When
        final Map<String, BigDecimal> categorySpending =
                analyticsService.getSpendingByCategory(testUser, startDate, endDate);

        // Then
        assertNotNull(categorySpending);
        assertEquals(BigDecimal.valueOf(100.00), categorySpending.get("FOOD"));
        assertEquals(BigDecimal.valueOf(75.00), categorySpending.get("TRANSPORTATION"));
    }

    @Test
    void testGetSpendingByCategoryWithNullCategoryIgnoresTransaction() {
        // Given
        final TransactionTable tx1 = createTransaction("tx-1", "FOOD", BigDecimal.valueOf(100.00));
        final TransactionTable tx2 =
                createTransaction("tx-2", null, BigDecimal.valueOf(50.00)); // Null category
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate))
                .thenReturn(Arrays.asList(tx1, tx2));

        // When
        final Map<String, BigDecimal> categorySpending =
                analyticsService.getSpendingByCategory(testUser, startDate, endDate);

        // Then
        assertNotNull(categorySpending);
        assertEquals(BigDecimal.valueOf(100.00), categorySpending.get("FOOD"));
        assertFalse(categorySpending.containsKey(null));
    }

    @Test
    void testGetSpendingByCategoryWithNullAmountIgnoresTransaction() {
        // Given
        final TransactionTable tx1 = createTransaction("tx-1", "FOOD", BigDecimal.valueOf(100.00));
        final TransactionTable tx2 = createTransaction("tx-2", "FOOD", null); // Null amount
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate))
                .thenReturn(Arrays.asList(tx1, tx2));

        // When
        final Map<String, BigDecimal> categorySpending =
                analyticsService.getSpendingByCategory(testUser, startDate, endDate);

        // Then
        assertNotNull(categorySpending);
        assertEquals(BigDecimal.valueOf(100.00), categorySpending.get("FOOD"));
    }

    // Helper methods
    private TransactionTable createTransaction(final String id, final BigDecimal amount) {
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId(testUser.getUserId());
        transaction.setAmount(amount);
        transaction.setTransactionDate(
                LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        return transaction;
    }

    private TransactionTable createTransaction(
            final String id, final String category, final BigDecimal amount) {
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId(testUser.getUserId());
        transaction.setAmount(amount);
        transaction.setCategoryPrimary(category);
        transaction.setCategoryDetailed(category);
        transaction.setTransactionDate(
                LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        return transaction;
    }
}
