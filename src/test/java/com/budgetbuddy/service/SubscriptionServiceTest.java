package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.SubscriptionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.ml.EnhancedCategoryDetectionService;
import com.budgetbuddy.service.ml.FuzzyMatchingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for SubscriptionService */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class SubscriptionServiceTest {

    private static final String NETFLIX = "Netflix";
    private static final String SUBSCRIPTIONS = "subscriptions";

    @Mock private SubscriptionRepository subscriptionRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private EnhancedCategoryDetectionService enhancedCategoryDetectionService;

    @Mock private FuzzyMatchingService fuzzyMatchingService;

    private SubscriptionService subscriptionService;

    private String testUserId;
    private List<TransactionTable> testTransactions;

    @BeforeEach
    void setUp() {
        testUserId = "test-user-123";
        testTransactions = new ArrayList<>();

        // Create SubscriptionService with mocked dependencies
        subscriptionService =
                new SubscriptionService(
                        subscriptionRepository,
                        transactionRepository,
                        enhancedCategoryDetectionService,
                        fuzzyMatchingService);
    }

    @Test
    void testDetectSubscriptionsMonthlySubscription() {
        // Given: 3 monthly transactions from the same merchant with the same amount
        final String merchantName = NETFLIX;
        final BigDecimal amount = new BigDecimal("-15.99");
        final LocalDate startDate = LocalDate.of(2024, 1, 15);

        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(merchantName, amount, startDate.plusMonths(i));
            testTransactions.add(tx);
        }

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        // When
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());

        final Subscription subscription = subscriptions.get(0);
        assertEquals(
                merchantName,
                subscription.getMerchantName()); // Use actual merchant name (not uppercase)
        assertEquals(amount, subscription.getAmount());
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, subscription.getFrequency());
        assertEquals(startDate, subscription.getStartDate());
        assertTrue(subscription.getActive());
        // Verify subscriptionCategory is set (Netflix should be "subscription")
        assertNotNull(subscription.getSubscriptionCategory(), "subscriptionCategory should be set");
    }

    @Test
    void testDetectSubscriptionsQuarterlySubscription() {
        // Given: 3 quarterly transactions
        final String merchantName = "Adobe";
        final BigDecimal amount = new BigDecimal("-52.99");
        final LocalDate startDate = LocalDate.of(2024, 1, 1);

        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(merchantName, amount, startDate.plusMonths(i * 3));
            testTransactions.add(tx);
        }

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        // When
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        assertEquals(
                Subscription.SubscriptionFrequency.QUARTERLY, subscriptions.get(0).getFrequency());
    }

    @Test
    void testDetectSubscriptionsAnnualSubscription() {
        // Given: 2 annual transactions
        final String merchantName = "Amazon Prime";
        final BigDecimal amount = new BigDecimal("-139.00");
        final LocalDate startDate = LocalDate.of(2023, 1, 1);

        for (int i = 0; i < 2; i++) {
            final TransactionTable tx =
                    createTransaction(merchantName, amount, startDate.plusYears(i));
            testTransactions.add(tx);
        }

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        // When
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        assertEquals(
                Subscription.SubscriptionFrequency.ANNUAL, subscriptions.get(0).getFrequency());
    }

    @Test
    void testDetectSubscriptionsNoSubscriptionsTooFewTransactions() {
        // Given: Only 1 transaction (need at least 2)
        final TransactionTable tx =
                createTransaction(NETFLIX, new BigDecimal("-15.99"), LocalDate.now());

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        // When
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertTrue(subscriptions.isEmpty());
    }

    @Test
    void testDetectSubscriptionsFiltersByCategory() {
        // Given: Transactions with subscription category
        final TransactionTable tx1 =
                createTransaction(NETFLIX, new BigDecimal("-15.99"), LocalDate.of(2024, 1, 15));
        final TransactionTable tx2 =
                createTransaction(NETFLIX, new BigDecimal("-15.99"), LocalDate.of(2024, 2, 15));

        testTransactions.add(tx1);
        testTransactions.add(tx2);

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        // When
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
    }

    @Test
    void testDetectSubscriptionsIgnoresNonExpenseTransactions() {
        // Given: Positive amount (income) transactions
        final TransactionTable tx1 =
                createTransaction("Salary", new BigDecimal("5000.00"), LocalDate.of(2024, 1, 1));
        final TransactionTable tx2 =
                createTransaction("Bonus", new BigDecimal("1000.00"), LocalDate.of(2024, 2, 1));

        testTransactions.add(tx1);
        testTransactions.add(tx2);

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        // When
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertTrue(subscriptions.isEmpty()); // Income transactions should be ignored
    }

    @Test
    void testGetSubscriptions() {
        // Given
        final com.budgetbuddy.model.dynamodb.SubscriptionTable table =
                new com.budgetbuddy.model.dynamodb.SubscriptionTable();
        table.setSubscriptionId("sub-123");
        table.setUserId(testUserId);
        table.setMerchantName(NETFLIX);
        table.setAmount(new BigDecimal("15.99"));
        table.setFrequency("MONTHLY");
        table.setStartDate("2024-01-15");
        table.setNextPaymentDate("2024-02-15");
        table.setCategory(SUBSCRIPTIONS);
        table.setActive(true);

        when(subscriptionRepository.findByUserId(testUserId)).thenReturn(List.of(table));

        // When
        final List<Subscription> subscriptions = subscriptionService.getSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        assertEquals(NETFLIX, subscriptions.get(0).getMerchantName());
    }

    @Test
    void testGetActiveSubscriptions() {
        // Given
        final com.budgetbuddy.model.dynamodb.SubscriptionTable activeTable =
                new com.budgetbuddy.model.dynamodb.SubscriptionTable();
        activeTable.setSubscriptionId("sub-123");
        activeTable.setUserId(testUserId);
        activeTable.setMerchantName(NETFLIX);
        activeTable.setAmount(new BigDecimal("15.99"));
        activeTable.setFrequency("MONTHLY");
        activeTable.setStartDate("2024-01-15");
        // CRITICAL: Set nextPaymentDate to a future date to ensure subscription is considered
        // active
        // The service filters out subscriptions with nextPaymentDate more than 30 days in the past
        activeTable.setNextPaymentDate(java.time.LocalDate.now().plusDays(10).toString());
        activeTable.setCategory(SUBSCRIPTIONS);
        activeTable.setActive(true);

        final com.budgetbuddy.model.dynamodb.SubscriptionTable inactiveTable =
                new com.budgetbuddy.model.dynamodb.SubscriptionTable();
        inactiveTable.setSubscriptionId("sub-456");
        inactiveTable.setUserId(testUserId);
        inactiveTable.setMerchantName("Cancelled Service");
        inactiveTable.setAmount(new BigDecimal("9.99"));
        inactiveTable.setFrequency("MONTHLY");
        inactiveTable.setStartDate("2024-01-01");
        inactiveTable.setNextPaymentDate("2024-02-01");
        inactiveTable.setCategory(SUBSCRIPTIONS);
        inactiveTable.setActive(false);

        when(subscriptionRepository.findActiveByUserId(testUserId))
                .thenReturn(List.of(activeTable));

        // When
        final List<Subscription> subscriptions =
                subscriptionService.getActiveSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        assertEquals(NETFLIX, subscriptions.get(0).getMerchantName());
        assertTrue(subscriptions.get(0).getActive());
    }

    @Test
    void testSaveSubscriptions() {
        // Given
        final Subscription subscription = new Subscription();
        subscription.setSubscriptionId("sub-123");
        subscription.setUserId(testUserId);
        subscription.setMerchantName(NETFLIX);
        subscription.setAmount(new BigDecimal("15.99"));
        subscription.setFrequency(Subscription.SubscriptionFrequency.MONTHLY);
        subscription.setStartDate(LocalDate.of(2024, 1, 15));
        subscription.setCategory(SUBSCRIPTIONS);
        subscription.setActive(true);

        // When
        subscriptionService.saveSubscriptions(testUserId, List.of(subscription));

        // Then
        verify(subscriptionRepository, times(1))
                .save(any(com.budgetbuddy.model.dynamodb.SubscriptionTable.class));
    }

    @Test
    void testDeleteSubscription() {
        // Given
        final String subscriptionId = "sub-123";

        // When
        subscriptionService.deleteSubscription(subscriptionId);

        // Then
        verify(subscriptionRepository, times(1)).delete(subscriptionId);
    }

    @Test
    void testDetectSubscriptionsSubscriptionCategoryNetflixIsSubscription() {
        // Given: Netflix transactions (known subscription merchant)
        final String merchantName = NETFLIX;
        final BigDecimal amount = new BigDecimal("-15.99");
        final LocalDate startDate = LocalDate.of(2024, 1, 15);

        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(merchantName, amount, startDate.plusMonths(i));
            tx.setCategoryPrimary("entertainment");
            tx.setCategoryDetailed("streaming");
            testTransactions.add(tx);
        }

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        // When
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        final Subscription subscription = subscriptions.get(0);
        assertEquals(
                "subscription",
                subscription.getSubscriptionCategory(),
                "Netflix should be categorized as 'subscription'");
    }

    @Test
    void testDetectSubscriptionsSubscriptionCategoryMortgageIsRecurring() {
        // Given: Mortgage/loan transactions (recurring payment)
        final String merchantName = "TD AUTO FINANCE";
        final BigDecimal amount = new BigDecimal("-355.17");
        final LocalDate startDate = LocalDate.of(2024, 1, 10);

        for (int i = 0; i < 3; i++) {
            final TransactionTable tx =
                    createTransaction(merchantName, amount, startDate.plusMonths(i));
            tx.setCategoryPrimary("payment");
            tx.setCategoryDetailed("payment");
            testTransactions.add(tx);
        }

        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        // When
        final List<Subscription> subscriptions =
                subscriptionService.detectSubscriptions(testUserId);

        // Then
        assertNotNull(subscriptions);
        assertEquals(1, subscriptions.size());
        final Subscription subscription = subscriptions.get(0);
        assertEquals(
                "recurring",
                subscription.getSubscriptionCategory(),
                "Mortgage/loan payment should be categorized as 'recurring'");
    }

    // Helper method to create test transactions
    private TransactionTable createTransaction(
            final String merchantName, final BigDecimal amount, final LocalDate date) {
        final TransactionTable tx = new TransactionTable();
        tx.setTransactionId(java.util.UUID.randomUUID().toString());
        tx.setUserId(testUserId);
        tx.setAccountId("account-123");
        tx.setMerchantName(merchantName);
        tx.setDescription(merchantName + " subscription");
        tx.setAmount(amount);
        tx.setTransactionDate(date.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        tx.setCategoryPrimary(SUBSCRIPTIONS);
        tx.setCategoryDetailed(SUBSCRIPTIONS);
        return tx;
    }
}
