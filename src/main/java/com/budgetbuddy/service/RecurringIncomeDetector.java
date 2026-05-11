package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.correctness.TransferClassifier;
import com.budgetbuddy.service.correctness.UserClock;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Flow 7 / O10 — recurring-income detection.
 *
 * <p>Finds paychecks (and any other rhythmic positive transaction) by grouping recent income rows
 * by merchant + amount (bucketed to the dollar to collapse tax wiggles), then measuring cadence. A
 * pattern qualifies when at least three charges line up around a recognisable cadence — 14 days
 * (biweekly), 15-16 (semi-monthly), or ~30 (monthly) — with ±3-day tolerance.
 *
 * <p>Output is a {@link RecurringIncome} record including the next expected date plus a 0–1
 * confidence score derived from sample count and variance.
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({
    "PMD.LawOfDemeter",
    "PMD.AvoidCatchingGenericException",
    "PMD.DataClass",
    "PMD.OnlyOneReturn"
})
@Service
public class RecurringIncomeDetector {
    private static final String DUE_DATE = "dueDate";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionRepository transactionRepository;
    private final UserClock userClock;

    public RecurringIncomeDetector(
            final TransactionRepository transactionRepository, final UserClock userClock) {
        this.transactionRepository = transactionRepository;
        this.userClock = userClock;
    }

    /** Public API for controllers: detect every recurring income stream for a user. */
    public List<RecurringIncome> detect(final String userId) {
        if (userId == null || userId.isEmpty()) {
            return List.of();
        }
        // User-local "today" so the 6-month window and the computed
        // next-expected date both align with how the user perceives weeks.
        final LocalDate end = userClock.today(userId);
        final LocalDate start = end.minusMonths(6);
        final List<TransactionTable> rows =
                transactionRepository.findByUserIdAndDateRange(
                        userId, start.format(DATE), end.format(DATE));

        // Bucket by payer + rounded amount.
        final Map<String, List<TransactionTable>> byKey = new HashMap<>();
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null || t.getAmount().signum() <= 0) {
                continue;
            }
            // Filter self-transfers (e.g. savings→checking). Plaid maps
            // TRANSFER_IN to "income" on ingest, so without this every
            // recurring savings sweep would surface as a paycheck.
            if (TransferClassifier.isTransfer(t)) {
                continue;
            }
            String merchant = t.getMerchantName();
            if (merchant == null || merchant.isBlank()) {
                merchant = t.getDescription();
            }
            if (merchant == null || merchant.isBlank()) {
                continue;
            }
            final String key =
                    merchant.trim().toLowerCase(Locale.ROOT)
                            + "|"
                            + t.getAmount().setScale(0, RoundingMode.HALF_UP);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        final List<RecurringIncome> out = new ArrayList<>();
        for (final var entry : byKey.entrySet()) {
            final var txs = entry.getValue();
            if (txs.size() < 3) {
                continue;
            }
            txs.sort(Comparator.comparing(TransactionTable::getTransactionDate));
            final double avgGap = averageGap(txs);
            final String cadence = classify(avgGap);
            if (cadence == null) {
                continue;
            }

            LocalDate last;
            try {
                last = LocalDate.parse(txs.get(txs.size() - 1).getTransactionDate());
            } catch (Exception e) {
                continue;
            }
            final int cadenceDays = Math.toIntExact(Math.round(avgGap));
            final LocalDate next = last.plusDays(cadenceDays);
            final double confidence = confidenceFrom(txs, avgGap);

            final RecurringIncome r = new RecurringIncome();
            r.userId = txs.get(0).getUserId();
            r.merchantName = txs.get(0).getMerchantName();
            r.amount = txs.get(txs.size() - 1).getAmount();
            r.cadence = cadence;
            r.cadenceDays = cadenceDays;
            r.lastSeen = last;
            r.nextExpected = next;
            r.confidence = confidence;
            r.sampleCount = txs.size();
            out.add(r);
        }
        return out;
    }

    /**
     * Project the next 30 days of cash flow given current recurring income and a recent expense
     * run-rate. Returns a per-day running balance delta starting at 0 so the chart doesn't imply
     * current balance knowledge.
     */
    public List<CashFlowPoint> projectThirtyDays(final String userId) {
        final List<RecurringIncome> incomes = detect(userId);
        final LocalDate end = userClock.today(userId);
        final LocalDate start = end.minusDays(30);

        // Daily spend run-rate from the last 30 days.
        final List<TransactionTable> rows =
                transactionRepository.findByUserIdAndDateRange(
                        userId, start.format(DATE), end.format(DATE));
        BigDecimal expenses = BigDecimal.ZERO;
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null) {
                continue;
            }
            if (t.getDeletedAt() != null) {
                continue;
            }
            // Exclude transfers from the expense burn — a transfer to savings
            // is not money leaving the user's balance sheet.
            if (TransferClassifier.isTransfer(t)) {
                continue;
            }
            if (t.getAmount().signum() < 0) {
                expenses = expenses.add(t.getAmount().abs());
            }
        }
        final BigDecimal dailyBurn = expenses.divide(new BigDecimal("30"), 2, RoundingMode.HALF_UP);

        // Seed expected-income dates forward 30 days.
        final Map<LocalDate, BigDecimal> incomeByDate = new HashMap<>();
        for (final RecurringIncome r : incomes) {
            LocalDate d = r.nextExpected;
            while (d != null && !d.isAfter(end.plusDays(30))) {
                incomeByDate.merge(d, r.amount, BigDecimal::add);
                d = d.plusDays(r.cadenceDays);
            }
        }

        final List<CashFlowPoint> points = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i <= 30; i++) {
            final LocalDate d = end.plusDays(i);
            final BigDecimal income = incomeByDate.getOrDefault(d, BigDecimal.ZERO);
            running = running.add(income).subtract(dailyBurn);
            final CashFlowPoint p = new CashFlowPoint();
            p.date = d;
            p.projectedIncome = income;
            p.projectedSpend = dailyBurn;
            p.runningBalance = running;
            points.add(p);
        }
        return points;
    }

    // ---- helpers ----

    private double averageGap(final List<TransactionTable> txs) {
        long totalDays = 0;
        int gaps = 0;
        for (int i = 1; i < txs.size(); i++) {
            try {
                final LocalDate a = LocalDate.parse(txs.get(i - 1).getTransactionDate());
                final LocalDate b = LocalDate.parse(txs.get(i).getTransactionDate());
                totalDays += ChronoUnit.DAYS.between(a, b);
                gaps++;
            } catch (DateTimeException ignored) {
                // skip pairs whose transaction date string can't be parsed
            }
        }
        return gaps == 0 ? 0 : (double) totalDays / gaps;
    }

    /** Round-trip the cadence to the nearest recognised interval. ±3-day tolerance. */
    private String classify(final double avgDays) {
        if (near(avgDays, 7)) {
            return "weekly";
        }
        if (near(avgDays, 14)) {
            return "biweekly";
        }
        if (avgDays >= 13 && avgDays <= 17) {
            return "semi-monthly";
        }
        if (avgDays >= 27 && avgDays <= 33) {
            return "monthly";
        }
        return null;
    }

    private boolean near(final double a, final double b) {
        return Math.abs(a - b) <= 3;
    }

    private double confidenceFrom(final List<TransactionTable> txs, final double avg) {
        // More samples + tighter variance = more confidence. Capped at 1.
        if (txs.size() < 3 || avg == 0) {
            return 0;
        }
        double variance = 0;
        int gaps = 0;
        for (int i = 1; i < txs.size(); i++) {
            try {
                final LocalDate a = LocalDate.parse(txs.get(i - 1).getTransactionDate());
                final LocalDate b = LocalDate.parse(txs.get(i).getTransactionDate());
                final long gap = ChronoUnit.DAYS.between(a, b);
                variance += Math.pow(gap - avg, 2);
                gaps++;
            } catch (DateTimeException ignored) {
                // skip pairs whose transaction date string can't be parsed
            }
        }
        final double stdev = Math.sqrt(variance / Math.max(1, gaps));
        final double tightness = Math.max(0, 1.0 - stdev / avg);
        final double volumeBonus = Math.min(1.0, txs.size() / 6.0);
        return Math.max(0, Math.min(1.0, (tightness * 0.7) + (volumeBonus * 0.3)));
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = {
                "URF_UNREAD_FIELD",
                "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD",
                "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD",
                "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"
            },
            justification = "DTO — fields are read/written by Jackson via reflection")
    public static class RecurringIncome {
        public String userId;
        public String merchantName;
        public BigDecimal amount;
        public String cadence;
        public int cadenceDays;
        public LocalDate lastSeen;
        public LocalDate nextExpected;
        public double confidence;
        public int sampleCount;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
            value = {
                "URF_UNREAD_FIELD",
                "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD",
                "UWF_UNWRITTEN_PUBLIC_OR_PROTECTED_FIELD",
                "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"
            },
            justification = "DTO — fields are read/written by Jackson via reflection")
    public static class CashFlowPoint {
        public LocalDate date;
        public BigDecimal projectedIncome;
        public BigDecimal projectedSpend;
        public BigDecimal runningBalance;
    }

    /**
     * Flow 8 / O5 — upcoming bills in the next {@code days}. Derives from detected recurring
     * OUTFLOW patterns (the negative-amount mirror of {@link #detect}) so we don't hit subscription
     * storage for this lightweight view.
     */
    public List<Map<String, Object>> upcomingBillsNextDays(final String userId, final int days) {
        if (userId == null) {
            return List.of();
        }
        final LocalDate today = LocalDate.now();
        final LocalDate horizon = today.plusDays(days);
        final LocalDate analysisStart = today.minusMonths(6);
        final List<TransactionTable> rows =
                transactionRepository.findByUserIdAndDateRange(
                        userId, analysisStart.format(DATE), today.format(DATE));

        final Map<String, List<TransactionTable>> byKey = new HashMap<>();
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null || t.getAmount().signum() >= 0) {
                continue;
            }
            if (t.getDeletedAt() != null) {
                continue;
            }
            String merchant = t.getMerchantName();
            if (merchant == null || merchant.isBlank()) {
                merchant = t.getDescription();
            }
            if (merchant == null || merchant.isBlank()) {
                continue;
            }
            final String key =
                    merchant.trim().toLowerCase(Locale.ROOT)
                            + "|"
                            + t.getAmount().abs().setScale(0, RoundingMode.HALF_UP);
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        final List<Map<String, Object>> out = new ArrayList<>();
        for (final var entry : byKey.entrySet()) {
            final var txs = entry.getValue();
            if (txs.size() < 3) {
                continue;
            }
            txs.sort(Comparator.comparing(TransactionTable::getTransactionDate));
            final double avgGap = averageGap(txs);
            if (avgGap < 25 || avgGap > 35) {
                continue; // monthly-ish only
            }
            final int cadence = (int) Math.round(avgGap);
            LocalDate last;
            try {
                last = LocalDate.parse(txs.get(txs.size() - 1).getTransactionDate());
            } catch (Exception e) {
                continue;
            }
            LocalDate next = last.plusDays(cadence);
            while (next.isBefore(today)) {
                next = next.plusDays(cadence);
            }
            while (!next.isAfter(horizon)) {
                final Map<String, Object> bill = new java.util.LinkedHashMap<>();
                bill.put("merchantName", txs.get(txs.size() - 1).getMerchantName());
                bill.put(
                        "amount",
                        txs.get(txs.size() - 1)
                                .getAmount()
                                .abs()
                                .setScale(2, RoundingMode.HALF_UP));
                bill.put(DUE_DATE, next.toString());
                bill.put("category", txs.get(txs.size() - 1).getCategoryPrimary());
                out.add(bill);
                next = next.plusDays(cadence);
            }
        }
        out.sort((a, b) -> ((String) a.get(DUE_DATE)).compareTo((String) b.get(DUE_DATE)));
        return out;
    }
}
