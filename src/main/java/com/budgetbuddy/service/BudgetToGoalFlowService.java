package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Flow 6 / O5 — credits goal progress from budgets that earmarked a `goalAllocation`.
 *
 * <p>Budgets added a {@code goalAllocation} field in Flow 5 — "of this $500/mo Groceries budget,
 * $100/mo goes toward my Vacation goal". The field rendered in the UI but didn't actually move
 * money. This service closes the loop.
 *
 * <p>Invoked after transaction ingest. For every budget with both a {@code goalId} and a positive
 * {@code goalAllocation}, it looks at the current cycle's under-spend (limit − spent) and credits
 * the smaller of (allocation, under-spend) to the goal's {@code currentAmount}. The logic is
 * intentionally conservative:
 *
 * <ul>
 *   <li>We only credit what the user has actually *saved* this cycle by not spending — never more
 *       than the allocation (user's intent), and never more than the under-spend (physical
 *       reality).
 *   <li>Goals in {@code .manual} mode are untouched — those are user-driven totals.
 *   <li>We stamp the credit amount in a per-budget marker field ({@code lastGoalFunded}) so a
 *       second call in the same cycle doesn't double-credit. Clearing happens in the month-end
 *       rollover job.
 * </ul>
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
public class BudgetToGoalFlowService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BudgetToGoalFlowService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;
    private final TransactionRepository transactionRepository;

    public BudgetToGoalFlowService(
            final BudgetRepository budgetRepository,
            final GoalRepository goalRepository,
            final TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
        this.transactionRepository = transactionRepository;
    }

    public void flowForUser(final UserTable user) {
        if (user == null || user.getUserId() == null) {
            return;
        }
        try {
            final List<BudgetTable> budgets = budgetRepository.findByUserId(user.getUserId());
            for (final BudgetTable b : budgets) {
                if (b.getGoalId() == null || b.getGoalId().isBlank()) {
                    continue;
                }
                if (b.getGoalAllocation() == null || b.getGoalAllocation().signum() <= 0) {
                    continue;
                }
                try {
                    flowOne(user, b);
                } catch (Exception e) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Budget→goal flow failed for budget {}: {}",
                                b.getBudgetId(),
                                e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        "Budget→goal flow pass failed for user {}: {}",
                        user.getUserId(),
                        e.getMessage());
            }
        }
    }

    private void flowOne(final UserTable user, final BudgetTable budget) {
        // Compute the month's spend for the budget's category.
        final LocalDate start = LocalDate.now().withDayOfMonth(1);
        final LocalDate end = LocalDate.now();
        final List<TransactionTable> rows =
                transactionRepository.findByUserIdAndDateRange(
                        user.getUserId(), start.format(DATE), end.format(DATE));

        BigDecimal spent = BigDecimal.ZERO;
        for (final TransactionTable t : rows) {
            if (t == null || t.getAmount() == null) {
                continue;
            }
            // Cross-flow audit fix: soft-deleted transactions were inflating under-spend
            // and over-crediting goals. Skip them.
            if (t.getDeletedAt() != null) {
                continue;
            }
            if (!(budget.getCategory().equals(t.getCategoryPrimary())
                    || budget.getCategory().equals(t.getCategoryDetailed()))) {
                continue;
            }
            // Cross-currency / pending transactions must not influence the under-spend math —
            // a canceled pending auth would over-credit the goal that gets reversed when the
            // pending row vanishes. See BudgetRolloverService.countsTowardBudget.
            if (!BudgetRolloverService.countsTowardBudget(budget, t)) {
                continue;
            }
            if (t.getAmount().signum() < 0) {
                spent = spent.add(t.getAmount().abs());
            }
        }

        BigDecimal effectiveLimit =
                budget.getMonthlyLimit() == null ? BigDecimal.ZERO : budget.getMonthlyLimit();
        if (Boolean.TRUE.equals(budget.getRolloverEnabled()) && budget.getCarriedAmount() != null) {
            effectiveLimit = effectiveLimit.add(budget.getCarriedAmount());
        }

        // Under-spend = money the user HAS kept in pocket this cycle. If they blew past
        // the limit, there's nothing to flow — the allocation is aspirational only.
        final BigDecimal underSpend = effectiveLimit.subtract(spent).max(BigDecimal.ZERO);
        final BigDecimal fundable =
                underSpend.min(budget.getGoalAllocation()).setScale(2, RoundingMode.HALF_UP);

        // Compare with what we've already credited this cycle.
        final BigDecimal alreadyFunded =
                budget.getLastGoalFunded() == null ? BigDecimal.ZERO : budget.getLastGoalFunded();
        final BigDecimal delta = fundable.subtract(alreadyFunded);
        if (delta.signum() <= 0) {
            // No additional funding — either user hasn't saved more, or we'd double-credit.
            return;
        }

        // Find the goal and bump its currentAmount (only if progressMode allows it).
        // Optimistic write with FULL-reload retry: on conflict we replace the in-memory
        // entity wholesale rather than copying three fields. Partial-field refresh would
        // re-save the stale `targetAmount` / `name` / `progressMode` / `accountIds` /
        // `deletedAt` we read at attempt-0, silently clobbering any concurrent user edits
        // to those fields. Completion + deletion are also re-checked after each refresh
        // so a racing "I'm done with this goal" write doesn't get reopened by our retry.
        final java.util.Optional<GoalTable> initialGoalOpt =
                goalRepository.findById(budget.getGoalId());
        if (initialGoalOpt.isEmpty()) {
            // Goal disappeared between budget read and now — nothing to credit.
            // Record cumulative funding below anyway so we don't keep retrying.
        } else {
            GoalTable current = initialGoalOpt.get();
            if ("manual".equalsIgnoreCase(current.getProgressMode())
                    || current.getDeletedAt() != null
                    || Boolean.TRUE.equals(current.getCompleted())) {
                // Goal isn't eligible for auto-credit; fall through to record funding.
            } else {
                for (int attempt = 0; attempt < 2; attempt++) {
                    BigDecimal newAmount =
                            (current.getCurrentAmount() == null
                                            ? BigDecimal.ZERO
                                            : current.getCurrentAmount())
                                    .add(delta);
                    if (current.getTargetAmount() != null
                            && newAmount.compareTo(current.getTargetAmount()) > 0) {
                        newAmount = current.getTargetAmount();
                    }
                    current.setCurrentAmount(newAmount);
                    current.setUpdatedAt(Instant.now());
                    try {
                        goalRepository.saveWithLock(current);
                        if (LOGGER.isInfoEnabled()) {
                            LOGGER.info(
                                    "Flowed {} from budget {} to goal {} (category={})",
                                    delta,
                                    budget.getBudgetId(),
                                    current.getGoalId(),
                                    budget.getCategory());
                        }
                        break;
                    } catch (
                            com.budgetbuddy.repository.dynamodb.OptimisticLockHelper
                                            .OptimisticLockException
                                    conflict) {
                        if (attempt == 1) {
                            if (LOGGER.isWarnEnabled()) {
                                LOGGER.warn(
                                        "Goal {} lost auto-credit of {} to a racing writer;"
                                                + " next ingest will retry",
                                        current.getGoalId(),
                                        delta);
                            }
                            break;
                        }
                        // Full-reload retry — picks up every concurrent edit, not just the
                        // three fields the old code copied.
                        final java.util.Optional<GoalTable> refreshedOpt =
                                goalRepository.findById(current.getGoalId());
                        if (refreshedOpt.isEmpty()) {
                            break;
                        }
                        current = refreshedOpt.get();
                        // Re-check eligibility — racer may have completed/deleted/converted
                        // the goal to manual mode while we were preparing our write.
                        if ("manual".equalsIgnoreCase(current.getProgressMode())
                                || current.getDeletedAt() != null
                                || Boolean.TRUE.equals(current.getCompleted())) {
                            break;
                        }
                    }
                }
            }
        }

        // Record the cumulative amount we've credited this cycle to dedupe future ingests.
        // Same optimistic-retry shape — the user may have just edited the
        // budget's goalAllocation. On conflict we re-read the budget, preserve
        // their edited fields, and only (re-)set the fields we own.
        BudgetTable working = budget;
        for (int attempt = 0; attempt < 2; attempt++) {
            working.setLastGoalFunded(fundable);
            working.setUpdatedAt(Instant.now());
            try {
                budgetRepository.saveWithLock(working);
                return;
            } catch (
                    com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                            conflict) {
                if (attempt == 1) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Budget {} lastGoalFunded update lost to concurrent user edit",
                                working.getBudgetId());
                    }
                    return;
                }
                final BudgetTable refreshed =
                        budgetRepository.findById(working.getBudgetId()).orElse(null);
                if (refreshed == null) {
                    return;
                }
                working = refreshed;
            }
        }
    }
}
