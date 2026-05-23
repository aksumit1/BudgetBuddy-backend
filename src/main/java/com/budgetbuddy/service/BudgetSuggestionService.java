package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * B-OPP-3: derive recommended monthly budget limits from a user's recent
 * spending history. The user-facing flow on iOS opens a "Create budget"
 * screen and we want to prefill the limit instead of forcing them to guess.
 *
 * <p>Algorithm: look back 6 months, group transactions by category, compute
 * the per-month spend per category, and recommend the median × 1.10 (10%
 * buffer) rounded up to the nearest $5. Median over mean because a single
 * holiday/vacation month otherwise drags the recommendation high.
 *
 * <p>Income/savings categories are excluded — there's no "limit" concept for
 * those.
 */
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.OnlyOneReturn", "PMD.DataClass"})
@Service
public class BudgetSuggestionService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int LOOKBACK_MONTHS = 6;
    private static final BigDecimal BUFFER_MULT = new BigDecimal("1.10");
    private static final BigDecimal ROUND_TO = new BigDecimal("5");

    private final TransactionRepository transactionRepository;
    /** Optional — present only when app.budget-suggestions.anthropic.enabled=true. */
    private final com.budgetbuddy.service.budget.BudgetLlmLimitAdvisor llmAdvisor;

    /** Test-friendly constructor: no LLM advisor. */
    public BudgetSuggestionService(final TransactionRepository transactionRepository) {
        this(transactionRepository, (com.budgetbuddy.service.budget.BudgetLlmLimitAdvisor) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BudgetSuggestionService(
            final TransactionRepository transactionRepository,
            final org.springframework.beans.factory.ObjectProvider<
                            com.budgetbuddy.service.budget.BudgetLlmLimitAdvisor>
                    advisorProvider) {
        this(transactionRepository, advisorProvider.getIfAvailable());
    }

    public BudgetSuggestionService(
            final TransactionRepository transactionRepository,
            final com.budgetbuddy.service.budget.BudgetLlmLimitAdvisor llmAdvisor) {
        this.transactionRepository = transactionRepository;
        this.llmAdvisor = llmAdvisor;
    }

    public List<BudgetSuggestion> suggestForUser(final UserTable user) {
        return suggestForUser(user, LocalDate.now());
    }

    /** Visible for tests so they can pin "now". */
    public List<BudgetSuggestion> suggestForUser(final UserTable user, final LocalDate now) {
        final LocalDate windowStart = now.minusMonths(LOOKBACK_MONTHS).withDayOfMonth(1);
        final LocalDate windowEnd = now;

        final List<TransactionTable> rows =
                transactionRepository.findByUserIdAndDateRange(
                        user.getUserId(), windowStart.format(DATE), windowEnd.format(DATE));

        // category -> yearMonth -> spend
        final Map<String, Map<String, BigDecimal>> byCatThenMonth = new HashMap<>();
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null || t.getDeletedAt() != null) continue;
            final String category =
                    t.getCategoryPrimary() != null ? t.getCategoryPrimary() : t.getCategoryDetailed();
            if (category == null || category.isEmpty()) continue;
            if (BudgetCategoryClassifier.isIncomeOrSavings(category)) continue;
            final LocalDate d = parseDate(t.getTransactionDate());
            if (d == null) continue;
            final String yearMonth = String.format("%04d-%02d", d.getYear(), d.getMonthValue());

            // Same refund-netting as BudgetSummaryService: positive amounts on
            // expense categories are refunds and reduce monthly spend.
            final BigDecimal delta =
                    t.getAmount().signum() < 0 ? t.getAmount().abs() : t.getAmount().negate();

            byCatThenMonth
                    .computeIfAbsent(category, k -> new HashMap<>())
                    .merge(yearMonth, delta, BigDecimal::add);
        }

        final List<BudgetSuggestion> out = new ArrayList<>();
        for (final Map.Entry<String, Map<String, BigDecimal>> e : byCatThenMonth.entrySet()) {
            final List<BigDecimal> monthlySpends =
                    e.getValue().values().stream()
                            .map(v -> v.signum() < 0 ? BigDecimal.ZERO : v)
                            .sorted()
                            .toList();
            if (monthlySpends.size() < 2) continue; // need >=2 months to call it a pattern

            final BigDecimal median = median(monthlySpends);
            if (median.signum() <= 0) continue;

            final BigDecimal recommended = roundUp(median.multiply(BUFFER_MULT));
            final BudgetSuggestion s = new BudgetSuggestion();
            s.category = e.getKey();
            s.recommendedMonthlyLimit = recommended;
            s.medianMonthlySpend = median.setScale(2, RoundingMode.HALF_UP);
            s.monthsObserved = monthlySpends.size();
            out.add(s);
        }

        out.sort(Comparator.comparing((BudgetSuggestion s) -> s.recommendedMonthlyLimit).reversed());
        // Stable shape so tests + clients can rely on category order being deterministic
        // when limits tie.
        final LinkedHashMap<String, BudgetSuggestion> dedup = new LinkedHashMap<>();
        for (final BudgetSuggestion s : out) dedup.putIfAbsent(s.category, s);
        final List<BudgetSuggestion> deduped = new ArrayList<>(dedup.values());
        // B-AI-1: if the LLM advisor is wired, let it annotate reasoning.
        // Advisor must never change the limit — only fill the reasoning
        // field. On any failure the rule-based output is returned unchanged.
        if (llmAdvisor != null && !deduped.isEmpty()) {
            try {
                return llmAdvisor.annotate(deduped);
            } catch (Exception e) {
                // Swallow — never let the advisor break the deterministic path.
                return deduped;
            }
        }
        return deduped;
    }

    private static BigDecimal median(final List<BigDecimal> sorted) {
        final int n = sorted.size();
        if (n == 0) return BigDecimal.ZERO;
        if ((n & 1) == 1) return sorted.get(n / 2);
        final BigDecimal a = sorted.get(n / 2 - 1);
        final BigDecimal b = sorted.get(n / 2);
        return a.add(b).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundUp(final BigDecimal value) {
        // Round UP to the nearest $5 so suggestions look intentional ($85, $90)
        // not noisy ($83.47).
        return value.divide(ROUND_TO, 0, RoundingMode.CEILING)
                .multiply(ROUND_TO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate parseDate(final String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return LocalDate.parse(s, DATE);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    @SuppressFBWarnings(
            value = {"URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD", "UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR"},
            justification = "DTO — fields read by Jackson via reflection")
    public static class BudgetSuggestion {
        public String category;
        public BigDecimal recommendedMonthlyLimit;
        public BigDecimal medianMonthlySpend;
        public int monthsObserved;
        /** B-AI-1: human-readable reasoning, present only when the LLM advisor is enabled. */
        public String reasoning;

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (!(o instanceof BudgetSuggestion that)) return false;
            return monthsObserved == that.monthsObserved
                    && Objects.equals(category, that.category)
                    && Objects.equals(recommendedMonthlyLimit, that.recommendedMonthlyLimit)
                    && Objects.equals(medianMonthlySpend, that.medianMonthlySpend)
                    && Objects.equals(reasoning, that.reasoning);
        }

        @Override
        public int hashCode() {
            return Objects.hash(category, recommendedMonthlyLimit, medianMonthlySpend, monthsObserved, reasoning);
        }
    }
}
