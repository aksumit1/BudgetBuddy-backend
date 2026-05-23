package com.budgetbuddy.service.budget;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.service.BudgetCategoryClassifier;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * B-AI-2: predictive overrun warning. Blends two signals:
 *
 * <ol>
 *   <li>Linear pace forecast: {@code spent / daysElapsed × totalDays}.
 *   <li>Historical baseline: median end-of-month spend for this user+category
 *       over the past 3 calendar months.
 * </ol>
 *
 * Takes the max of the two as the predicted final-cycle spend. A slow start
 * month still surfaces risk if past months always ended high, and an
 * unusually heavy first week still surfaces risk even if past months were
 * tame.
 *
 * <p>Returns a {@link Forecast} with a {@code risk} label
 * (LOW/MEDIUM/HIGH) and a one-line reason string suitable for direct
 * display.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn", "PMD.DataClass"})
@Service
public class BudgetForecastService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int HISTORY_MONTHS = 3;
    private static final BigDecimal MEDIUM_THRESHOLD = new BigDecimal("0.95");
    private static final BigDecimal HIGH_THRESHOLD = new BigDecimal("1.10");

    private final TransactionRepository transactionRepository;

    public BudgetForecastService(final TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public Forecast forecast(
            final String userId,
            final String category,
            final BigDecimal effectiveLimit,
            final BigDecimal currentSpent,
            final LocalDate cycleStart,
            final LocalDate cycleEndInclusive,
            final LocalDate today) {
        if (userId == null
                || category == null
                || effectiveLimit == null
                || effectiveLimit.signum() <= 0) {
            return null;
        }
        if (BudgetCategoryClassifier.isIncomeOrSavings(category)) {
            return null;
        }

        final long totalDays =
                java.time.temporal.ChronoUnit.DAYS.between(cycleStart, cycleEndInclusive) + 1;
        final LocalDate clampedNow =
                today.isBefore(cycleStart)
                        ? cycleStart
                        : (today.isAfter(cycleEndInclusive) ? cycleEndInclusive : today);
        final long daysElapsed =
                java.time.temporal.ChronoUnit.DAYS.between(cycleStart, clampedNow) + 1;

        final BigDecimal pace =
                currentSpent
                        .divide(BigDecimal.valueOf(Math.max(1L, daysElapsed)), 2, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(totalDays))
                        .setScale(2, RoundingMode.HALF_UP);

        final BigDecimal historical = historicalMonthEnd(userId, category, cycleStart);
        final BigDecimal predicted = historical == null ? pace : pace.max(historical);

        final BigDecimal ratio = predicted.divide(effectiveLimit, 4, RoundingMode.HALF_UP);

        final Risk risk;
        if (ratio.compareTo(HIGH_THRESHOLD) >= 0) {
            risk = Risk.HIGH;
        } else if (ratio.compareTo(MEDIUM_THRESHOLD) >= 0) {
            risk = Risk.MEDIUM;
        } else {
            risk = Risk.LOW;
        }

        final String reason =
                switch (risk) {
                    case HIGH -> String.format(
                            "Projected $%s of $%s limit by cycle end — likely overrun",
                            predicted.toPlainString(), effectiveLimit.toPlainString());
                    case MEDIUM -> String.format(
                            "Tracking close to limit: projected $%s of $%s",
                            predicted.toPlainString(), effectiveLimit.toPlainString());
                    case LOW -> String.format(
                            "On pace: projected $%s of $%s",
                            predicted.toPlainString(), effectiveLimit.toPlainString());
                };

        final Forecast f = new Forecast();
        f.category = category;
        f.predictedSpend = predicted;
        f.paceSpend = pace;
        f.historicalMedianSpend = historical;
        f.risk = risk;
        f.reason = reason;
        return f;
    }

    private BigDecimal historicalMonthEnd(
            final String userId, final String category, final LocalDate cycleStart) {
        final YearMonth currentYm = YearMonth.from(cycleStart);
        final YearMonth start = currentYm.minusMonths(HISTORY_MONTHS);
        final LocalDate qStart = start.atDay(1);
        final LocalDate qEnd = currentYm.minusMonths(1).atEndOfMonth();
        if (qEnd.isBefore(qStart)) return null;

        final List<TransactionTable> rows;
        try {
            rows =
                    transactionRepository.findByUserIdAndDateRange(
                            userId, qStart.format(DATE), qEnd.format(DATE));
        } catch (Exception e) {
            return null;
        }

        final Map<YearMonth, BigDecimal> perMonth = new HashMap<>();
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null || t.getDeletedAt() != null) continue;
            if (!Objects.equals(t.getCategoryPrimary(), category)
                    && !Objects.equals(t.getCategoryDetailed(), category)) {
                continue;
            }
            final LocalDate d = parseDate(t.getTransactionDate());
            if (d == null) continue;
            final YearMonth ym = YearMonth.from(d);
            // Refund-netting matches BudgetSummaryService.
            final BigDecimal delta =
                    t.getAmount().signum() < 0 ? t.getAmount().abs() : t.getAmount().negate();
            perMonth.merge(ym, delta, BigDecimal::add);
        }
        if (perMonth.isEmpty()) return null;
        final List<BigDecimal> sorted = new ArrayList<>(perMonth.values());
        sorted.replaceAll(v -> v.signum() < 0 ? BigDecimal.ZERO : v);
        sorted.sort(BigDecimal::compareTo);
        final int n = sorted.size();
        if (n == 0) return null;
        if ((n & 1) == 1) return sorted.get(n / 2);
        return sorted.get(n / 2 - 1)
                .add(sorted.get(n / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static LocalDate parseDate(final String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDate.parse(s, DATE);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    public enum Risk {
        LOW,
        MEDIUM,
        HIGH
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class Forecast {
        public String category;
        public BigDecimal predictedSpend;
        public BigDecimal paceSpend;
        public BigDecimal historicalMedianSpend;
        public Risk risk;
        public String reason;
    }
}
