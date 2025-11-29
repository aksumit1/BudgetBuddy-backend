package com.budgetbuddy.analytics;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.TransactionService;
import com.budgetbuddy.service.aws.CloudWatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for AnalyticsService
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class AnalyticsServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionService transactionService;

    @Mock
    private CloudWatchService cloudWatchService;

    @InjectMocks
    private AnalyticsService analyticsService;

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
    void testGetSpendingSummary_WithValidInput_ReturnsSummary() {
        // Given
        BigDecimal totalSpending = BigDecimal.valueOf(1000.00);
        List<TransactionTable> transactions = Arrays.asList(
                createTransaction("tx-1", BigDecimal.valueOf(500.00)),
                createTransaction("tx-2", BigDecimal.valueOf(500.00))
        );

        when(transactionService.getTotalSpending(testUser, startDate, endDate)).thenReturn(totalSpending);
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate)).thenReturn(transactions);
        doNothing().when(cloudWatchService).putMetric(anyString(), anyDouble(), anyString());

        // When
        AnalyticsService.SpendingSummary summary = analyticsService.getSpendingSummary(testUser, startDate, endDate);

        // Then
        assertNotNull(summary);
        assertEquals(totalSpending, summary.getTotalSpending());
        assertEquals(2, summary.getTransactionCount());
        verify(cloudWatchService, times(2)).putMetric(anyString(), anyDouble(), anyString());
    }

    @Test
    void testGetSpendingSummary_WithNullTotalSpending_ReturnsZero() {
        // Given
        when(transactionService.getTotalSpending(testUser, startDate, endDate)).thenReturn(null);
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate)).thenReturn(List.of());

        // When
        AnalyticsService.SpendingSummary summary = analyticsService.getSpendingSummary(testUser, startDate, endDate);

        // Then
        assertNotNull(summary);
        assertEquals(BigDecimal.ZERO, summary.getTotalSpending());
    }

    @Test
    void testGetSpendingByCategory_WithValidInput_ReturnsCategoryMap() {
        // Given
        List<TransactionTable> transactions = Arrays.asList(
                createTransaction("tx-1", "FOOD", BigDecimal.valueOf(100.00)),
                createTransaction("tx-2", "FOOD", BigDecimal.valueOf(50.00)),
                createTransaction("tx-3", "TRANSPORTATION", BigDecimal.valueOf(75.00))
        );

        when(transactionService.getTransactionsInRange(testUser, startDate, endDate)).thenReturn(transactions);

        // When
        Map<String, BigDecimal> categorySpending = analyticsService.getSpendingByCategory(testUser, startDate, endDate);

        // Then
        assertNotNull(categorySpending);
        assertEquals(BigDecimal.valueOf(150.00), categorySpending.get("FOOD"));
        assertEquals(BigDecimal.valueOf(75.00), categorySpending.get("TRANSPORTATION"));
    }

    @Test
    void testGetSpendingByCategory_WithNullCategory_IgnoresTransaction() {
        // Given
        TransactionTable tx1 = createTransaction("tx-1", "FOOD", BigDecimal.valueOf(100.00));
        TransactionTable tx2 = createTransaction("tx-2", null, BigDecimal.valueOf(50.00));
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate))
                .thenReturn(Arrays.asList(tx1, tx2));

        // When
        Map<String, BigDecimal> categorySpending = analyticsService.getSpendingByCategory(testUser, startDate, endDate);

        // Then
        assertNotNull(categorySpending);
        assertEquals(BigDecimal.valueOf(100.00), categorySpending.get("FOOD"));
        assertFalse(categorySpending.containsKey(null));
    }

    @Test
    void testGetSpendingByCategory_WithNullAmount_IgnoresTransaction() {
        // Given
        TransactionTable tx1 = createTransaction("tx-1", "FOOD", BigDecimal.valueOf(100.00));
        TransactionTable tx2 = createTransaction("tx-2", "FOOD", null);
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate))
                .thenReturn(Arrays.asList(tx1, tx2));

        // When
        Map<String, BigDecimal> categorySpending = analyticsService.getSpendingByCategory(testUser, startDate, endDate);

        // Then
        assertNotNull(categorySpending);
        assertEquals(BigDecimal.valueOf(100.00), categorySpending.get("FOOD"));
    }

    // Helper methods
    private TransactionTable createTransaction(final String id, final BigDecimal amount) {
        return createTransaction(id, "FOOD", amount);
    }

    private TransactionTable createTransaction(final String id, final String category, final BigDecimal amount) {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId(testUser.getUserId());
        transaction.setAmount(amount);
        transaction.setCategoryPrimary(category);
        transaction.setCategoryDetailed(category);
        transaction.setTransactionDate(LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        return transaction;
    }
}

