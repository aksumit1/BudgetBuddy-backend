package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.MerchantSpendTrendService.TrendResult;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom")
final class MerchantSpendTrendServiceTest {

    // Pick a Thursday so the bucket-start calculation has something to do.
    private static final LocalDate NOW = LocalDate.of(2026, 5, 14);
    private static final String USER = "u1";

    @Test
    void weeklySeriesIsZeroFilledAndContiguous() {
        // 4-week window with one charge in the most recent week.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(charge("Netflix", "12.99", LocalDate.of(2026, 5, 12))));

        final TrendResult r =
                new MerchantSpendTrendService(repo).trend(USER, "Netflix", 4, NOW);
        assertEquals(4, r.weeklySeries.size(),
                "Series must include every week in the window, zero-filled");
        // Last bucket should hold the charge; earlier ones must be zero.
        final BigDecimal lastAmount = r.weeklySeries.get(3).amount;
        assertEquals(new BigDecimal("12.99"), lastAmount);
        for (int i = 0; i < 3; i++) {
            assertEquals(BigDecimal.ZERO.setScale(2), r.weeklySeries.get(i).amount,
                    "Missing weeks must zero-fill, not drop");
        }
    }

    @Test
    void refundsReduceTheBucketButCannotPushNegative() {
        // Charge $10 + refund $50 in the same week → net would be -40 but
        // the sparkline must clamp at 0 (no negative-spend bars).
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        charge("Coffee", "10", LocalDate.of(2026, 5, 12)),
                        refund("Coffee", "50", LocalDate.of(2026, 5, 12))));

        final TrendResult r =
                new MerchantSpendTrendService(repo).trend(USER, "Coffee", 2, NOW);
        for (final MerchantSpendTrendService.WeekBucket b : r.weeklySeries) {
            assertTrue(
                    b.amount.signum() >= 0,
                    "Weekly bucket must never go negative; got " + b.amount);
        }
    }

    @Test
    void merchantNormalisationMatchesAcrossPunctuationAndCase() {
        // "DJ BARRONS" should match "D.J. Barrons" / "dj barrons" / "DJ-BARRONS".
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        charge("D.J. Barrons", "5", LocalDate.of(2026, 5, 12)),
                        charge("dj barrons", "7", LocalDate.of(2026, 5, 13)),
                        charge("DJ-BARRONS", "3", LocalDate.of(2026, 5, 14))));

        final TrendResult r =
                new MerchantSpendTrendService(repo).trend(USER, "DJ BARRONS", 2, NOW);
        assertEquals(new BigDecimal("15.00"), r.totalSpend,
                "Normalisation must merge variants of the same merchant");
    }

    @Test
    void risingTrendIsLabelledAccordingly() {
        // Increasing weekly spend → positive slope → RISING.
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of(
                        charge("Spotify", "10", NOW.minusWeeks(3)),
                        charge("Spotify", "20", NOW.minusWeeks(2)),
                        charge("Spotify", "30", NOW.minusWeeks(1)),
                        charge("Spotify", "40", NOW)));

        final TrendResult r =
                new MerchantSpendTrendService(repo).trend(USER, "Spotify", 4, NOW);
        assertEquals("RISING", r.trendLabel);
        assertTrue(r.trendSlope > 0);
    }

    @Test
    void emptyWindowProducesAllZeroSeries() {
        final TransactionRepository repo = mock(TransactionRepository.class);
        when(repo.findByUserIdAndDateRange(eq(USER), anyString(), anyString()))
                .thenReturn(List.of());

        final TrendResult r =
                new MerchantSpendTrendService(repo).trend(USER, "Nothing", 8, NOW);
        assertEquals(8, r.weeklySeries.size());
        assertEquals(BigDecimal.ZERO.setScale(2), r.totalSpend);
        assertEquals(0, r.weeksWithSpend);
        assertEquals("FLAT", r.trendLabel);
    }

    private static TransactionTable charge(
            final String merchant, final String amount, final LocalDate date) {
        final TransactionTable t = new TransactionTable();
        t.setMerchantName(merchant);
        t.setAmount(new BigDecimal(amount).negate()); // canonical: charges are negative
        t.setTransactionDate(date.toString());
        return t;
    }

    private static TransactionTable refund(
            final String merchant, final String amount, final LocalDate date) {
        final TransactionTable t = new TransactionTable();
        t.setMerchantName(merchant);
        t.setAmount(new BigDecimal(amount)); // canonical: refunds are positive
        t.setTransactionDate(date.toString());
        return t;
    }
}
