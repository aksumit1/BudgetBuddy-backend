package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins behaviours added to {@link SubscriptionInsightsService} during
 * the audit:
 *
 * <ul>
 *   <li>BUG-7: per-frequency unused threshold replaces cadence*2
 *   <li>BUG-8 + OPP-2: lifecycle state transitions (UNUSED_1_CYCLE →
 *       UNUSED_2_CYCLES → PRESUMED_CANCELLED + active=false)
 *   <li>RISK-4: configurable price-change threshold
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionInsightsServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private SubscriptionService subscriptionService;

    private SubscriptionInsightsService insights;
    private final String userId = "u";

    @BeforeEach
    void setUp() {
        // Default threshold 5.0%; override per test where needed.
        insights = new SubscriptionInsightsService(
                transactionRepository, subscriptionService, 5.0);
    }

    private Subscription sub(
            final String merchant,
            final Subscription.SubscriptionFrequency freq,
            final LocalDate lastPayment) {
        final Subscription s = new Subscription();
        s.setSubscriptionId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setMerchantName(merchant);
        s.setAmount(new BigDecimal("-15.99"));
        s.setFrequency(freq);
        s.setLastPaymentDate(lastPayment);
        s.setActive(true);
        return s;
    }

    @Test
    void annualSubFlagsAt90DaysLateNotTwoYears() {
        // BUG-7: with the old `cadence*2` rule, an annual sub needed to be
        // ~2 years late to flag. New per-frequency cap is 90d AFTER
        // expectedNextPayment (which is lastPayment + 365). So a sub
        // whose last payment was 460 days ago (= 365 + 95) should now
        // flag, where it would have needed ~2 years before.
        final Subscription s = sub("AcmeAnnual", Subscription.SubscriptionFrequency.ANNUAL,
                LocalDate.now().minusDays(460));
        when(subscriptionService.getActiveSubscriptions(eq(userId)))
                .thenReturn(List.of(s));
        when(transactionRepository.findByUserId(eq(userId), eq(0), eq(10_000)))
                .thenReturn(List.of(
                        anyTxFor("AcmeAnnual", LocalDate.now().minusDays(460))));

        final var list = insights.detectUnusedSubscriptions(userId);
        assertFalse(list.isEmpty(),
                "Annual sub 460d overdue should flag at the new 90d-after-expected threshold");
    }

    @Test
    void lifecycleProgression() {
        // OPP-2: ascending date-since-last-payment should produce
        // UNUSED_1_CYCLE -> UNUSED_2_CYCLES -> PRESUMED_CANCELLED.
        // Monthly threshold = 60 days; UNUSED_2 cutoff is 1.5*60 = 90;
        // PRESUMED_CANCELLED cutoff is 2*60 = 120.

        // Just past the 1.5x threshold (between UNUSED_1 and UNUSED_2)
        final Subscription a = sub("A", Subscription.SubscriptionFrequency.MONTHLY,
                LocalDate.now().minusDays(70));   // 70 >= 60 (UNUSED_1)
        final Subscription b = sub("B", Subscription.SubscriptionFrequency.MONTHLY,
                LocalDate.now().minusDays(100));  // >= 90 (UNUSED_2)
        final Subscription c = sub("C", Subscription.SubscriptionFrequency.MONTHLY,
                LocalDate.now().minusDays(130));  // >= 120 (PRESUMED_CANCELLED)

        when(subscriptionService.getActiveSubscriptions(eq(userId)))
                .thenReturn(List.of(a, b, c));
        when(transactionRepository.findByUserId(eq(userId), eq(0), eq(10_000)))
                .thenReturn(List.of(
                        anyTxFor("A", LocalDate.now().minusDays(70)),
                        anyTxFor("B", LocalDate.now().minusDays(100)),
                        anyTxFor("C", LocalDate.now().minusDays(130))));

        insights.detectUnusedSubscriptions(userId);

        assertEquals(Subscription.LifecycleState.UNUSED_1_CYCLE, a.getLifecycleState());
        assertEquals(Subscription.LifecycleState.UNUSED_2_CYCLES, b.getLifecycleState());
        assertEquals(Subscription.LifecycleState.PRESUMED_CANCELLED, c.getLifecycleState());
        assertTrue(a.getActive());
        assertTrue(b.getActive());
        assertFalse(c.getActive(), "PRESUMED_CANCELLED should also flip active=false");
    }

    @Test
    void configurableThresholdRejectsUnderLimitChanges() {
        // RISK-4: with threshold = 20%, a 10% price change should NOT alert.
        final SubscriptionInsightsService strict = new SubscriptionInsightsService(
                transactionRepository, subscriptionService, /*threshold=*/20.0);
        final Subscription s = new Subscription();
        s.setSubscriptionId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setMerchantName("Test");
        s.setAmount(new BigDecimal("-100.00"));   // current price $100
        s.setFrequency(Subscription.SubscriptionFrequency.MONTHLY);
        s.setLastPaymentDate(LocalDate.now().minusDays(7));
        s.setActive(true);
        // priceHistory contains prior price of $90 — that's a 10% change.
        s.setPriceHistory(List.of(new Subscription.PriceHistoryEntry(
                new BigDecimal("-90.00"), LocalDate.now().minusDays(40))));

        when(subscriptionService.getActiveSubscriptions(eq(userId)))
                .thenReturn(List.of(s));

        final var alerts = strict.detectPriceChanges(userId);
        assertTrue(alerts.isEmpty(),
                "10% change should not alert when threshold is 20%");
    }

    private TransactionTable anyTxFor(final String merchant, final LocalDate date) {
        final TransactionTable tx = new TransactionTable();
        tx.setTransactionId(UUID.randomUUID().toString());
        tx.setUserId(userId);
        tx.setMerchantName(merchant);
        tx.setDescription(merchant);
        tx.setAmount(new BigDecimal("-15.99"));
        tx.setTransactionDate(date.toString());
        return tx;
    }
}
