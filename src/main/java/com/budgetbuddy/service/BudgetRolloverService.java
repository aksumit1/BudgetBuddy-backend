package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Flow 5 / O2 — month-end rollover job.
 *
 * <p>Before this service, the {@code carriedAmount} field was declarative only: users could toggle
 * "rollover" on a budget, but nothing ever recomputed the carried number at month-end. Users who
 * left rollover on saw $0 carry forever; the feature was effectively a label.
 *
 * <p>Now a scheduled task runs at 00:30 UTC on the 1st of every month. For every budget with {@code
 * rolloverEnabled == true} it:
 *
 * <ol>
 *   <li>Computes the prior month's spend in that budget's category (expense categories only —
 *       income/savings budgets use a different "progress toward target" semantic that doesn't roll
 *       over).
 *   <li>Derives surplus = effectiveLimit − spent. Positive = money left on the table; negative =
 *       over-budget (rolls forward as debt, reducing next month's cap).
 *   <li>Writes the new {@code carriedAmount} and bumps {@code updatedAt} so clients see the new
 *       value on their next incremental sync.
 * </ol>
 *
 * <p>Manual trigger available via {@link #runRollover(LocalDate)} for testing and for users who
 * want an early roll (e.g. after migrating mid-month).
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
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class BudgetRolloverService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BudgetRolloverService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final BudgetRepository budgetRepository;
    private final TransactionRepository transactionRepository;
    private final DistributedLockService distributedLock;

    public BudgetRolloverService(
            final BudgetRepository budgetRepository,
            final TransactionRepository transactionRepository,
            final DistributedLockService distributedLock) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
        this.distributedLock = distributedLock;
    }

    /**
     * Cron: 00:30 UTC, on the 1st of every month. Offset 30 min past midnight to let any in-flight
     * end-of-month transaction ingests land before we close the books. Guarded by a distributed
     * lock so when ECS auto-scales to N tasks the rollover runs exactly once — otherwise each task
     * would double-credit every rollover-enabled budget at the start of the month.
     */
    @Scheduled(cron = "0 30 0 1 * *", zone = "UTC")
    public void monthlyRollover() {
        final LocalDate today = LocalDate.now(ZoneOffset.UTC);
        final String lockKey = "budgetRollover:" + today;
        distributedLock.runOnce(lockKey, 60, () -> runRollover(today));
    }

    /**
     * Roll surplus/deficit for the month that preceded {@code anchor}. Package-visible so tests can
     * drive it deterministically.
     */
    public RolloverResult runRollover(final LocalDate anchor) {
        final LocalDate priorMonthStart = anchor.minusMonths(1).withDayOfMonth(1);
        final LocalDate priorMonthEnd = anchor.withDayOfMonth(1).minusDays(1);

        int updated = 0;
        int skipped = 0;
        final List<BudgetTable> withRollover = budgetRepository.findAllWithRollover();
        LOGGER.info("Rollover scan found {} budgets with rollover enabled", withRollover.size());

        for (final BudgetTable budget : withRollover) {
            try {
                // Income/savings categories aren't expense caps; rolling them forward
                // would produce nonsense. Skip them. (Income tracking is additive, not
                // subtractive.)
                if (isIncomeOrSavingsCategory(budget.getCategory())) {
                    skipped++;
                    continue;
                }
                if (budget.getMonthlyLimit() == null || budget.getUserId() == null) {
                    skipped++;
                    continue;
                }

                final BigDecimal spent = computeSpend(budget, priorMonthStart, priorMonthEnd);
                final BigDecimal existingCarry =
                        budget.getCarriedAmount() != null
                                ? budget.getCarriedAmount()
                                : BigDecimal.ZERO;
                final BigDecimal effectiveLimit = budget.getMonthlyLimit().add(existingCarry);
                final BigDecimal newCarry =
                        effectiveLimit.subtract(spent).setScale(2, RoundingMode.HALF_UP);

                budget.setCarriedAmount(newCarry);
                // Clear the O8 alert bookmark so the next month can alert again at 50/75/90/100.
                budget.setLastAlertedThreshold(null);
                // Clear the O5 goal-flow bookmark so the new month starts from zero credited.
                budget.setLastGoalFunded(null);
                budget.setUpdatedAt(Instant.now());
                budgetRepository.save(budget);
                updated++;
                LOGGER.debug(
                        "Rolled budget {} — prior spent {}, new carry {}",
                        budget.getBudgetId(),
                        spent,
                        newCarry);
            } catch (Exception e) {
                // Keep going; one bad row shouldn't stop the whole job.
                LOGGER.warn(
                        "Rollover failed for budget {}: {}", budget.getBudgetId(), e.getMessage());
                skipped++;
            }
        }
        LOGGER.info("Monthly rollover complete: {} updated, {} skipped", updated, skipped);
        return new RolloverResult(updated, skipped, priorMonthStart, priorMonthEnd);
    }

    private BigDecimal computeSpend(
            final BudgetTable budget, final LocalDate start, final LocalDate end) {
        final List<TransactionTable> rows =
                transactionRepository.findByUserIdAndDateRange(
                        budget.getUserId(), start.format(DATE), end.format(DATE));
        BigDecimal spent = BigDecimal.ZERO;
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null) {
                continue;
            }
            if (!(budget.getCategory().equals(t.getCategoryPrimary())
                    || budget.getCategory().equals(t.getCategoryDetailed()))) {
                continue;
            }
            if (!countsTowardBudget(budget, t)) {
                // Skip cross-currency or pending transactions — see countsTowardBudget.
                continue;
            }
            if (t.getAmount().signum() < 0) {
                // Expense rows use negative amounts — treat the magnitude as the spend.
                spent = spent.add(t.getAmount().abs());
            }
        }
        return spent;
    }

    /**
     * Returns true when {@code transaction} should count toward {@code budget}'s spend total.
     * Either currency code being null is treated as "legacy / best effort match" so we don't
     * regress single-currency users who pre-date the currencyCode column. Comparison is
     * case-insensitive because some import paths normalize to uppercase ("USD") while others
     * preserve client-supplied casing.
     */
    static boolean matchesBudgetCurrency(final BudgetTable budget, final TransactionTable t) {
        final String bc = budget.getCurrencyCode();
        final String tc = t.getCurrencyCode();
        if (bc == null || bc.isBlank() || tc == null || tc.isBlank()) {
            return true;
        }
        return bc.equalsIgnoreCase(tc);
    }

    /**
     * Plaid (and most CSV imports) mark a transaction {@code pending=true} until it posts. Pending
     * transactions can have their amount adjusted (restaurant tip, fuel pump final), or be reversed
     * entirely if the merchant cancels the auth. Counting them toward budget threshold alerts
     * causes two bugs:
     *
     * <ol>
     *   <li>An alert fires at 50% based on the pending amount, then the post comes in at a
     *       different number and the user sees the alert reverse and re-fire.
     *   <li>A canceled pending auth permanently inflates carry-forward at month-end.
     * </ol>
     *
     * <p>So every budget aggregator filters {@code pending == true} out — posted-only is the source
     * of truth for budget arithmetic. Returns true when the transaction is posted or its pending
     * flag is unset (null = pre-Plaid era data, assume posted).
     */
    static boolean isPosted(final TransactionTable t) {
        return !Boolean.TRUE.equals(t.getPending());
    }

    /**
     * Convenience predicate combining currency match and posted-only. Use this at call sites in
     * preference to inlining both checks; keeps the rule single-source-of-truth.
     */
    static boolean countsTowardBudget(final BudgetTable budget, final TransactionTable t) {
        return matchesBudgetCurrency(budget, t) && isPosted(t);
    }

    private boolean isIncomeOrSavingsCategory(final String category) {
        if (category == null) {
            return false;
        }
        final String c = category.toLowerCase(Locale.ROOT);
        return "income".equals(c)
                || "salary".equals(c)
                || "investment".equals(c)
                || "savings".equals(c)
                || "interest".equals(c);
    }

    public record RolloverResult(
            int updated, int skipped, LocalDate priorStart, LocalDate priorEnd) {}
}
