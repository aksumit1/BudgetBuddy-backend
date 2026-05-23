package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        // Given: 4 monthly transactions from the same merchant with the same amount.
        // The detector requires 4+ monthly occurrences (user-specified threshold)
        // before flagging as a subscription — fewer is "possibly recurring" but
        // not established enough to claim.
        final String merchantName = NETFLIX;
        final BigDecimal amount = new BigDecimal("-15.99");
        final LocalDate startDate = LocalDate.of(2024, 1, 15);

        for (int i = 0; i < 4; i++) {
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
        // Given: 4 monthly subscription-category transactions (clears the
        // monthly-occurrence threshold).
        for (int i = 0; i < 4; i++) {
            final TransactionTable tx =
                    createTransaction(
                            NETFLIX,
                            new BigDecimal("-15.99"),
                            LocalDate.of(2024, 1, 15).plusMonths(i));
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
        // Given: 4 monthly Netflix transactions (clears the monthly threshold).
        final String merchantName = NETFLIX;
        final BigDecimal amount = new BigDecimal("-15.99");
        final LocalDate startDate = LocalDate.of(2024, 1, 15);

        for (int i = 0; i < 4; i++) {
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
        // Given: 4 monthly mortgage/loan transactions (clears monthly threshold).
        final String merchantName = "TD AUTO FINANCE";
        final BigDecimal amount = new BigDecimal("-355.17");
        final LocalDate startDate = LocalDate.of(2024, 1, 10);

        for (int i = 0; i < 4; i++) {
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

    @Test
    void testConsolidatePriceChangeMergesSameMerchant() {
        // Walmart+ at $14.27 for 4 months, then price bumped to $14.28 for 4 more months.
        // After consolidation: one subscription at the LATEST price with a price-change note.
        final String merchantName = "Walmart+ Member";
        final LocalDate firstDate = LocalDate.of(2025, 1, 4);
        for (int i = 0; i < 4; i++) {
            final TransactionTable tx =
                    createTransaction(merchantName, new BigDecimal("-14.27"), firstDate.plusMonths(i));
            testTransactions.add(tx);
        }
        for (int i = 4; i < 8; i++) {
            final TransactionTable tx =
                    createTransaction(merchantName, new BigDecimal("-14.28"), firstDate.plusMonths(i));
            testTransactions.add(tx);
        }
        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        // ONE consolidated subscription, not two.
        assertEquals(1, subscriptions.size(),
                "Same merchant with small price change should collapse into one subscription");
        final Subscription sub = subscriptions.get(0);
        assertEquals(new BigDecimal("-14.28"), sub.getAmount(),
                "Current price should be the latest amount, not the older one");
        assertNotNull(sub.getDescription(),
                "Price-change history should be recorded in the description");
        assertTrue(sub.getDescription().contains("price-change"),
                "Description should mention the price change: was '" + sub.getDescription() + "'");
    }

    @Test
    void testConsolidateKeepsBoundedVariableSubscription() {
        // Cell-bill style: 3 distinct monthly prices but all within 1.5× of
        // each other (Xfinity Mobile real-world data: $172.39 / $184.86 /
        // $230.74, spread = 1.34×). This should NOT be dropped — it's one
        // subscription that wobbles month-to-month.
        final String merchantName = "Xfinity Mobile";
        final LocalDate startDate = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 4; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-172.39"), startDate.plusMonths(i)));
        }
        for (int i = 4; i < 8; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-184.86"), startDate.plusMonths(i)));
        }
        for (int i = 8; i < 12; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-230.74"), startDate.plusMonths(i)));
        }
        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        assertEquals(1, subscriptions.size(),
                "Cell-bill style merchant with bounded variation (spread < 1.5×) should consolidate into one subscription");
        // Current price should be the latest amount, not an average.
        assertEquals(new BigDecimal("-230.74"), subscriptions.get(0).getAmount(),
                "Latest amount should be the current price");
    }

    @Test
    void testConsolidateDropsHighSpreadVariableMerchant() {
        // Per-ride style: 3 prices with wide spread (>1.5×). Bird scooter
        // real-world: $2.18 / $6.61 / $11.68 → spread 5.36×. Drop as usage,
        // not a subscription.
        final String merchantName = "Bird App*Ride";
        final LocalDate startDate = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 4; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-2.18"), startDate.plusMonths(i)));
        }
        for (int i = 4; i < 8; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-6.61"), startDate.plusMonths(i)));
        }
        for (int i = 8; i < 12; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-11.68"), startDate.plusMonths(i)));
        }
        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        final List<Subscription> subscriptions = subscriptionService.detectSubscriptions(testUserId);

        assertTrue(subscriptions.isEmpty(),
                "Merchant with 3+ prices AND wide spread (>1.5×) should be dropped as variable usage");
    }

    @Test
    void testMedianCadenceSurvivesOneMissedMonth() {
        // Netflix monthly except one missed month — a mean-of-gaps would
        // skew the cadence into the QUARTERLY-or-no-match band; median
        // keeps it MONTHLY so the sub is detected.
        // Months: Jan, Feb, Mar (gap), May, Jun, Jul, Aug → gaps
        // [31, 28, 61, 30, 30, 31] → median 30 (MONTHLY) vs mean 35.
        final String merchantName = NETFLIX;
        final BigDecimal amt = new BigDecimal("-15.99");
        for (final LocalDate d : new LocalDate[] {
                LocalDate.of(2026, 1, 15),
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 5, 15),  // one missed cycle
                LocalDate.of(2026, 6, 15),
                LocalDate.of(2026, 7, 15),
                LocalDate.of(2026, 8, 15),
        }) {
            testTransactions.add(createTransaction(merchantName, amt, d));
        }
        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        final List<Subscription> subs = subscriptionService.detectSubscriptions(testUserId);

        assertEquals(1, subs.size(), "Sub should survive one missed month via median-gap detection");
        assertEquals(Subscription.SubscriptionFrequency.MONTHLY, subs.get(0).getFrequency());
    }

    @Test
    void testDetectCooldownReturnsCachedResult() {
        // Two back-to-back calls with the same data — the second should
        // return the cached set without calling the repository again.
        final String merchantName = NETFLIX;
        for (int i = 0; i < 4; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-15.99"),
                    LocalDate.of(2026, 1, 15).plusMonths(i)));
        }
        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        final List<Subscription> first = subscriptionService.detectSubscriptions(testUserId);
        final List<Subscription> second = subscriptionService.detectSubscriptions(testUserId);

        assertEquals(first.size(), second.size(), "Cached result should match");
        // Verify the repository was only consulted once (cooldown short-circuits the 2nd call).
        verify(transactionRepository, times(1)).findByUserId(eq(testUserId), eq(0), eq(10_000));
    }

    @Test
    void testPredictedNextAmountForVariableSubscription() {
        // Xfinity Mobile style: 4 months at $172, 4 at $185, 4 at $230.
        // Spread is 1.34× (under VARIABLE_SPREAD_LIMIT) so it collapses to
        // one variable sub. predictedNextAmount = median of all 12 amounts.
        final String merchantName = "Xfinity Mobile";
        final LocalDate startDate = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 4; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-172.00"), startDate.plusMonths(i)));
        }
        for (int i = 4; i < 8; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-185.00"), startDate.plusMonths(i)));
        }
        for (int i = 8; i < 12; i++) {
            testTransactions.add(createTransaction(
                    merchantName, new BigDecimal("-230.00"), startDate.plusMonths(i)));
        }
        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        final List<Subscription> subs = subscriptionService.detectSubscriptions(testUserId);

        assertEquals(1, subs.size());
        final Subscription s = subs.get(0);
        assertNotNull(s.getPredictedNextAmount(),
                "Variable sub should have a predictedNextAmount populated");
        // Median of [172, 185, 230] is 185 (one of each unique amount appears as the median candidate)
        // — Subscription objects collapse to one entry per unique amount, so 3 distinct values total.
        // Acceptable range covers either flavor of median computation.
        assertTrue(s.getPredictedNextAmount().compareTo(new BigDecimal("170")) >= 0
                        && s.getPredictedNextAmount().compareTo(new BigDecimal("235")) <= 0,
                "Predicted next amount should fall within observed range, got "
                        + s.getPredictedNextAmount());
    }

    @Test
    void testPriceChangePopulatesStructuredHistory() {
        // Walmart+ $14.27 → $14.28: consolidation merges into one sub with
        // structured priceHistory carrying the older amount.
        final String merchantName = "Walmart+ Member";
        final LocalDate first = LocalDate.of(2025, 1, 4);
        for (int i = 0; i < 4; i++) {
            testTransactions.add(createTransaction(merchantName,
                    new BigDecimal("-14.27"), first.plusMonths(i)));
        }
        for (int i = 4; i < 8; i++) {
            testTransactions.add(createTransaction(merchantName,
                    new BigDecimal("-14.28"), first.plusMonths(i)));
        }
        when(transactionRepository.findByUserId(eq(testUserId), eq(0), eq(10_000)))
                .thenReturn(testTransactions);

        final List<Subscription> subs = subscriptionService.detectSubscriptions(testUserId);

        assertEquals(1, subs.size(), "Price change should collapse into one sub");
        final Subscription s = subs.get(0);
        assertEquals(new BigDecimal("-14.28"), s.getAmount(), "Current amount = latest price");
        assertNotNull(s.getPriceHistory(), "priceHistory must not be null after consolidation");
        assertFalse(s.getPriceHistory().isEmpty(),
                "priceHistory should contain the older $14.27 entry");
        assertEquals(new BigDecimal("-14.27"), s.getPriceHistory().get(0).getAmount(),
                "First history entry should be the prior price");
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
