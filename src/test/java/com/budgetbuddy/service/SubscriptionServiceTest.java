package com.budgetbuddy.service;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SubscriptionService
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private SubscriptionService subscriptionService;

    private String testUserId;
    private List<TransactionTable> testTransactions;

    @BeforeEach
    void setUp() {
        testUserId = "test-user-123";
        testTransactions = new ArrayList<>();
    }

    @Test
    void testDetectSubscriptions_MonthlySubscription() {
        // Given: 3 monthly transactions from the same merchant with the same amount
        String merchantName = "Netflix";
        BigDecimal amount = new BigDecimal("-15.99");
        LocalDate startDate = LocalDate.of(2024, 1, 15);
        
        for (int i = 0; i < 3; i++) {
            TransactionTable tx = createTransaction(
                merchantName,
                amount,
                startDate.plusMonths(i)
            );
            testTransactions.add(tx);
        }

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10000)))
            .thenReturn(testTransactions);

        // When
        List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        
        Subscription subscription = subscriptions.get(0);
        assertEquals(merchantName.toLowerCase(), subscription.getMerchantName());
        assertEquals(amount, subscription.getAmount());
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, subscription.getFrequency());
        assertEquals(startDate, subscription.getStartDate());
        assertTrue(subscription.getActive());
    }

    @Test
    void testDetectSubscriptions_QuarterlySubscription() {
        // Given: 3 quarterly transactions
        String merchantName = "Adobe";
        BigDecimal amount = new BigDecimal("-52.99");
        LocalDate startDate = LocalDate.of(2024, 1, 1);
        
        for (int i = 0; i < 3; i++) {
            TransactionTable tx = createTransaction(
                merchantName,
                amount,
                startDate.plusMonths(i * 3)
            );
            testTransactions.add(tx);
        }

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10000)))
            .thenReturn(testTransactions);

        // When
        List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        assertEquals(Subscription.SubscriptionFrequency.QUARTERLY, subscriptions.get(0).getFrequency());
    }

    @Test
    void testDetectSubscriptions_AnnualSubscription() {
        // Given: 2 annual transactions
        String merchantName = "Amazon Prime";
        BigDecimal amount = new BigDecimal("-139.00");
        LocalDate startDate = LocalDate.of(2023, 1, 1);
        
        for (int i = 0; i < 2; i++) {
            TransactionTable tx = createTransaction(
                merchantName,
                amount,
                startDate.plusYears(i)
            );
            testTransactions.add(tx);
        }

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10000)))
            .thenReturn(testTransactions);

        // When
        List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        assertEquals(Subscription.SubscriptionFrequency.ANNUAL, subscriptions.get(0).getFrequency());
    }

    @Test
    void testDetectSubscriptions_NoSubscriptions_TooFewTransactions() {
        // Given: Only 1 transaction (need at least 2)
        TransactionTable tx = createTransaction("Netflix", new BigDecimal("-15.99"), LocalDate.now());
        testTransactions.add(tx);

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10000)))
            .thenReturn(testTransactions);

        // When
        List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertTrue(subscriptions.isEmpty());
    }

    @Test
    void testDetectSubscriptions_FiltersByCategory() {
        // Given: Transactions with subscription category
        TransactionTable tx1 = createTransaction("Netflix", new BigDecimal("-15.99"), LocalDate.of(2024, 1, 15));
        tx1.setCategoryPrimary("subscriptions");
        TransactionTable tx2 = createTransaction("Netflix", new BigDecimal("-15.99"), LocalDate.of(2024, 2, 15));
        tx2.setCategoryPrimary("subscriptions");
        
        testTransactions.add(tx1);
        testTransactions.add(tx2);

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10000)))
            .thenReturn(testTransactions);

        // When
        List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
    }

    @Test
    void testDetectSubscriptions_IgnoresNonExpenseTransactions() {
        // Given: Positive amount (income) transactions
        TransactionTable tx1 = createTransaction("Salary", new BigDecimal("5000.00"), LocalDate.of(2024, 1, 1));
        TransactionTable tx2 = createTransaction("Salary", new BigDecimal("5000.00"), LocalDate.of(2024, 2, 1));
        
        testTransactions.add(tx1);
        testTransactions.add(tx2);

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10000)))
            .thenReturn(testTransactions);

        // When
        List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertTrue(subscriptions.isEmpty()); // Income transactions should be ignored
    }

    @Test
    void testGetSubscriptions() {
        // Given
        com.budgetbuddy.model.dynamodb.SubscriptionTable table = new com.budgetbuddy.model.dynamodb.SubscriptionTable();
        table.setSubscriptionId("sub-123");
        table.setUserId(testUserId);
        table.setMerchantName("Netflix");
        table.setAmount(new BigDecimal("15.99"));
        table.setFrequency("MONTHLY");
        table.setStartDate("2024-01-15");
        table.setNextPaymentDate("2024-02-15");
        table.setCategory("subscriptions");
        table.setActive(true);

        when(subscriptionRepository.findByUserId(testUserId))
            .thenReturn(List.of(table));

        // When
        List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        assertEquals("Netflix", subscriptions.get(0).getMerchantName());
    }

    @Test
    void testGetActiveSubscriptions() {
        // Given
        com.budgetbuddy.model.dynamodb.SubscriptionTable activeTable = new com.budgetbuddy.model.dynamodb.SubscriptionTable();
        activeTable.setSubscriptionId("sub-123");
        activeTable.setUserId(testUserId);
        activeTable.setMerchantName("Netflix");
        activeTable.setAmount(new BigDecimal("15.99"));
        activeTable.setFrequency("MONTHLY");
        activeTable.setStartDate("2024-01-15");
        activeTable.setNextPaymentDate("2024-02-15");
        activeTable.setCategory("subscriptions");
        activeTable.setActive(true);

        com.budgetbuddy.model.dynamodb.SubscriptionTable inactiveTable = new com.budgetbuddy.model.dynamodb.SubscriptionTable();
        inactiveTable.setSubscriptionId("sub-456");
        inactiveTable.setUserId(testUserId);
        inactiveTable.setMerchantName("Cancelled Service");
        inactiveTable.setAmount(new BigDecimal("9.99"));
        inactiveTable.setFrequency("MONTHLY");
        inactiveTable.setStartDate("2024-01-01");
        inactiveTable.setNextPaymentDate("2024-02-01");
        inactiveTable.setCategory("subscriptions");
        inactiveTable.setActive(false);

        when(subscriptionRepository.findActiveByUserId(testUserId))
            .thenReturn(List.of(activeTable));

        // When
        List<Subscription> subscriptions = subscriptionService.getActiveSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        assertEquals("Netflix", subscriptions.get(0).getMerchantName());
        assertTrue(subscriptions.get(0).getActive());
    }

    @Test
    void testSaveSubscriptions() {
        // Given
        Subscription subscription = new Subscription();
        subscription.setSubscriptionId("sub-123");
        subscription.setUserId(testUserId);
        subscription.setMerchantName("Netflix");
        subscription.setAmount(new BigDecimal("15.99"));
        subscription.setFrequency(Subscription.SubscriptionFrequency.MONTHLY);
        subscription.setStartDate(LocalDate.of(2024, 1, 15));
        subscription.setCategory("subscriptions");
        subscription.setActive(true);

        // When
        subscriptionService.saveSubscriptions(testUserId, List.of(subscription));

        // Then
        verify(subscriptionRepository, times(1)).save(any(com.budgetbuddy.model.dynamodb.SubscriptionTable.class));
    }

    @Test
    void testDeleteSubscription() {
        // Given
        String subscriptionId = "sub-123";

        // When
        subscriptionService.deleteSubscription(subscriptionId);

        // Then
        verify(subscriptionRepository, times(1)).delete(subscriptionId);
    }

    // Helper method to create test transactions
    private TransactionTable createTransaction(String merchantName, BigDecimal amount, LocalDate date) {
        TransactionTable tx = new TransactionTable();
        tx.setTransactionId(java.util.UUID.randomUUID().toString());
        tx.setUserId(testUserId);
        tx.setAccountId("account-123");
        tx.setMerchantName(merchantName);
        tx.setDescription(merchantName + " subscription");
        tx.setAmount(amount);
        tx.setTransactionDate(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        tx.setCategoryPrimary("subscriptions");
        tx.setCategoryDetailed("subscriptions");
        return tx;
    }
}

