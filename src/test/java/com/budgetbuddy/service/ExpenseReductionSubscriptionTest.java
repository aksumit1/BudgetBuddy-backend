package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.Subscription;
import com.budgetbuddy.model.Subscription.SubscriptionFrequency;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.ExpenseReductionService.ExpenseRecommendation;
import com.budgetbuddy.service.ExpenseReductionService.Priority;
import com.budgetbuddy.service.ExpenseReductionService.RecommendationType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the fix for {@code isSubscriptionUnused}. Transaction data is
 * billing data, not engagement data — so the prior "Cancel unused
 * subscription" recommendation was making a usage claim it couldn't
 * back up. The corrected behaviour:
 *
 * <ul>
 *   <li>The detector is frequency-aware: annual subscriptions aren't
 *       flagged after a normal 60-day gap.</li>
 *   <li>The recommendation copy says "no charge in the expected billing
 *       window — verify and remove" instead of "Cancel unused".</li>
 *   <li>Priority is MEDIUM, not HIGH — we're not confident enough to
 *       push this to the top of the user's action list.</li>
 *   <li>Subscriptions with empty merchant names are never flagged.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ExpenseReductionSubscriptionTest {

    private static final String USER = "u1";

    @Mock private TransactionRepository transactionRepository;
    @Mock private SubscriptionService subscriptionService;

    private ExpenseReductionService svc;

    @BeforeEach
    void setUp() {
        svc = new ExpenseReductionService(transactionRepository, subscriptionService);
        lenient().when(transactionRepository.findByUserIdAndDateRange(
                        anyString(), anyString(), anyString()))
                .thenReturn(new ArrayList<>());
    }

    @Test
    void noRecentCharge_recommendsVerifyNotCancel() {
        // Monthly subscription, no charges in 60 days → flagged.
        when(subscriptionService.getSubscriptions(USER))
                .thenReturn(List.of(activeSub("Netflix", "15.49", SubscriptionFrequency.MONTHLY)));

        final List<ExpenseRecommendation> recs = svc.getRecommendations(USER);
        final List<ExpenseRecommendation> cancels = recs.stream()
                .filter(r -> r.getType() == RecommendationType.CANCEL).toList();
        assertEquals(1, cancels.size());

        final ExpenseRecommendation rec = cancels.get(0);
        // Copy must NOT claim the subscription is unused — we can't know
        // that from billing data.
        assertFalse(rec.getDescription().toLowerCase().contains("unused"),
                "Recommendation copy must not claim 'unused' — billing data can't prove usage");
        assertTrue(rec.getDescription().toLowerCase().contains("verify")
                        || rec.getDescription().toLowerCase().contains("may already"),
                "Copy should ask the user to verify, not assert cancellation");
        assertEquals(Priority.MEDIUM, rec.getPriority(),
                "MEDIUM priority — we're not certain enough for HIGH");
    }

    @Test
    void annualSubscription_notFlagged_within2YearWindow() {
        // The old code used a fixed 60-day window. An annual Adobe sub
        // that last charged 5 months ago would have been wrongly flagged.
        // Fix: ANNUAL frequency widens the window to ~24 months, so a
        // 5-month gap is normal and the merchant transaction (which IS
        // within the window) suppresses the flag.
        when(subscriptionService.getSubscriptions(USER))
                .thenReturn(List.of(activeSub("Adobe", "239.88", SubscriptionFrequency.ANNUAL)));
        // The annual charge from 5 months ago — well inside the new
        // 760-day annual window — must be found.
        final ArgumentCaptor<String> startCap = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> endCap = ArgumentCaptor.forClass(String.class);
        when(transactionRepository.findByUserIdAndDateRange(
                eq(USER), startCap.capture(), endCap.capture()))
                .thenReturn(List.of(merchantTx("Adobe", "-239.88")));

        final List<ExpenseRecommendation> recs = svc.getRecommendations(USER);
        // Adobe charge is present in the window, so no CANCEL flag.
        assertTrue(recs.stream()
                        .noneMatch(r -> r.getType() == RecommendationType.CANCEL
                                && r.getTitle().equals("Adobe")),
                "Annual sub with a charge in the lookback window must not be flagged");
        // And the window must actually be ~24 months for at least one
        // of the calls (the subscription analysis call — other code
        // paths use the smaller category-overspending window).
        final List<String> starts = startCap.getAllValues();
        final List<String> ends = endCap.getAllValues();
        long maxDays = 0;
        for (int i = 0; i < starts.size(); i++) {
            final long d = java.time.LocalDate.parse(ends.get(i)).toEpochDay()
                    - java.time.LocalDate.parse(starts.get(i)).toEpochDay();
            if (d > maxDays) {
                maxDays = d;
            }
        }
        assertTrue(maxDays >= 700,
                "ANNUAL frequency should produce a lookback window of at least ~700 days, got "
                        + maxDays);
    }

    @Test
    void monthlySubscription_chargedRecently_notFlagged() {
        when(subscriptionService.getSubscriptions(USER))
                .thenReturn(List.of(activeSub("Netflix", "15.49", SubscriptionFrequency.MONTHLY)));
        when(transactionRepository.findByUserIdAndDateRange(
                eq(USER), anyString(), anyString()))
                .thenReturn(List.of(merchantTx("Netflix", "-15.49")));

        final List<ExpenseRecommendation> recs = svc.getRecommendations(USER);
        assertTrue(recs.stream()
                        .noneMatch(r -> r.getType() == RecommendationType.CANCEL
                                && r.getTitle().equals("Netflix")));
    }

    @Test
    void emptyMerchantName_neverFlagged_evenWithNoTransactions() {
        // Subscription with missing merchant name — we can't honestly
        // search for charges, so we must not flag (false-positive
        // cancellation prompts erode trust faster than missing signals).
        final Subscription sub = activeSub("", "9.99", SubscriptionFrequency.MONTHLY);
        when(subscriptionService.getSubscriptions(USER)).thenReturn(List.of(sub));

        final List<ExpenseRecommendation> recs = svc.getRecommendations(USER);
        assertTrue(recs.stream()
                        .noneMatch(r -> r.getType() == RecommendationType.CANCEL));
    }

    @Test
    void nullFrequency_fallsBackToLegacy60DayWindow() {
        // Records that pre-date the frequency field must still get the
        // historical 60-day behaviour, not zero coverage.
        final Subscription sub = activeSub("OldRecord", "10.00", null);
        when(subscriptionService.getSubscriptions(USER)).thenReturn(List.of(sub));
        final ArgumentCaptor<String> startCap = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> endCap = ArgumentCaptor.forClass(String.class);
        when(transactionRepository.findByUserIdAndDateRange(
                eq(USER), startCap.capture(), endCap.capture()))
                .thenReturn(new ArrayList<>());

        svc.getRecommendations(USER);
        // The subscription-analysis call uses our lookback. Other paths
        // may use larger windows (90-day category window), so check that
        // at least one call has the expected 60-day window.
        final List<String> starts = startCap.getAllValues();
        final List<String> ends = endCap.getAllValues();
        boolean saw60 = false;
        for (int i = 0; i < starts.size(); i++) {
            final long d = java.time.LocalDate.parse(ends.get(i)).toEpochDay()
                    - java.time.LocalDate.parse(starts.get(i)).toEpochDay();
            if (d == 60L) {
                saw60 = true;
                break;
            }
        }
        assertTrue(saw60, "Legacy default must remain 60 days for null-frequency subscriptions");
    }

    private static Subscription activeSub(
            final String merchant, final String amount, final SubscriptionFrequency freq) {
        final Subscription s = new Subscription();
        s.setSubscriptionId("sub-" + merchant);
        s.setUserId(USER);
        s.setMerchantName(merchant);
        s.setAmount(new BigDecimal(amount));
        s.setFrequency(freq);
        s.setActive(Boolean.TRUE);
        s.setStartDate(LocalDate.now().minusYears(1));
        return s;
    }

    private static TransactionTable merchantTx(final String merchant, final String amount) {
        final TransactionTable tx = new TransactionTable();
        tx.setDescription(merchant + " MONTHLY");
        tx.setMerchantName(merchant);
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionDate(LocalDate.now().minusDays(15).toString());
        return tx;
    }
}
