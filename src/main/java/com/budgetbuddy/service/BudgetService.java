package com.budgetbuddy.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Budget Service */
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
public class BudgetService {

    private static final String USER_IS_REQUIRED = "User is required";

    private static final Logger LOGGER = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepository budgetRepository;
    private final com.budgetbuddy.repository.dynamodb.TransactionRepository transactionRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    public BudgetService(
            final BudgetRepository budgetRepository,
            final com.budgetbuddy.repository.dynamodb.TransactionRepository transactionRepository) {
        this.budgetRepository = budgetRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Create or update budget
     *
     * @param budgetId Optional budget ID from app. If provided and valid, use it for consistency.
     *     If not provided, generate deterministic ID from user + category.
     * @param rolloverEnabled Optional flag to enable budget rollover/carryover. If null, defaults
     *     to false.
     * @param carriedAmount Optional amount carried from previous month. If null, defaults to zero.
     * @param goalId Optional ID of the goal this budget is linked to. If null, budget is not linked
     *     to any goal.
     */
    public BudgetTable createOrUpdateBudget(
            final UserTable user,
            final String category,
            final BigDecimal monthlyLimit,
            final String budgetId,
            final Boolean rolloverEnabled,
            final BigDecimal carriedAmount,
            final String goalId) {
        return createOrUpdateBudget(
                user,
                category,
                monthlyLimit,
                budgetId,
                rolloverEnabled,
                carriedAmount,
                goalId,
                null,
                null,
                null);
    }

    /**
     * Flow 5 — full-surface create/update. Adds `goalAllocation` (portion of the limit earmarked
     * for the linked goal), `period` (weekly/biweekly/monthly), and explicit `currencyCode`. Legacy
     * callers keep working via the overload above.
     *
     * <p>Rejects {@code goalAllocation &gt; monthlyLimit} and {@code goalAllocation != null}
     * without a {@code goalId} — both are nonsensical and would silently drift the "remaining to
     * allocate" math on the ZBB banner.
     */
    public BudgetTable createOrUpdateBudget(
            final UserTable user,
            final String category,
            final BigDecimal monthlyLimit,
            final String budgetId,
            final Boolean rolloverEnabled,
            final BigDecimal carriedAmount,
            final String goalId,
            final BigDecimal goalAllocation,
            final String period,
            final String currencyCode) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (category == null || category.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }
        if (monthlyLimit == null || monthlyLimit.compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Monthly limit must be non-negative");
        }
        if (goalAllocation != null) {
            if (goalAllocation.compareTo(BigDecimal.ZERO) < 0) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT, "Goal allocation must be non-negative");
            }
            if (goalAllocation.compareTo(monthlyLimit) > 0) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT, "Goal allocation can't exceed the monthly limit");
            }
            if (goalId == null || goalId.isBlank()) {
                throw new AppException(
                        ErrorCode.INVALID_INPUT, "Goal allocation requires a linked goalId");
            }
        }
        if (period != null
                && !period.isBlank()
                && !"monthly".equals(period)
                && !"weekly".equals(period)
                && !"biweekly".equals(period)) {
            throw new AppException(
                    ErrorCode.INVALID_INPUT, "period must be one of monthly|weekly|biweekly");
        }

        final Optional<BudgetTable> existing =
                budgetRepository.findByUserIdAndCategory(user.getUserId(), category);

        BudgetTable budget;
        if (existing.isPresent()) {
            budget = existing.get();
            budget.setMonthlyLimit(monthlyLimit);
            if (rolloverEnabled != null) {
                budget.setRolloverEnabled(rolloverEnabled);
            }
            if (carriedAmount != null) {
                budget.setCarriedAmount(carriedAmount);
            }
            if (goalId != null) {
                budget.setGoalId(goalId);
            }
            if (goalAllocation != null) {
                // Cross-flow audit fix: when the allocation changes mid-month, reset
                // the lastGoalFunded bookmark. Otherwise the BudgetToGoalFlowService
                // delta check (`fundable − alreadyFunded`) would under- or over-credit
                // the goal relative to the NEW allocation. Clearing the marker makes
                // the next ingest re-credit from scratch for the new amount.
                final BigDecimal previousAllocation = budget.getGoalAllocation();
                if (previousAllocation == null
                        || previousAllocation.compareTo(goalAllocation) != 0) {
                    budget.setLastGoalFunded(null);
                }
                budget.setGoalAllocation(goalAllocation);
            }
            if (period != null && !period.isBlank()) {
                budget.setPeriod(period);
            }
            if (currencyCode != null && !currencyCode.isBlank()) {
                budget.setCurrencyCode(currencyCode);
            }
        } else {
            budget = new BudgetTable();

            // Use provided budgetId if valid, otherwise generate deterministic ID
            if (budgetId != null
                    && !budgetId.isEmpty()
                    && com.budgetbuddy.util.IdGenerator.isValidUUID(budgetId)) {
                // CRITICAL FIX: Normalize ID to lowercase before checking for existing
                // This ensures we check with the normalized ID that will be saved
                final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(budgetId);

                // Check if budget with this ID already exists (using normalized ID)
                final Optional<BudgetTable> existingById = budgetRepository.findById(normalizedId);
                if (existingById.isPresent()) {
                    BudgetTable existingByIdTable = existingById.get();
                    // CRITICAL FIX: Verify the existing budget belongs to the same user and
                    // category
                    // This ensures idempotent behavior - return existing budget instead of throwing
                    // error
                    if (existingByIdTable.getUserId().equals(user.getUserId())
                            && existingByIdTable.getCategory().equals(category)) {
                        // Same budget (same user, same category) - update and
                        // return (idempotent). Under optimistic concurrency:
                        // the threshold evaluator may have just bumped
                        // lastAlertedThreshold on this row, and without lock
                        // that bump would overwrite the new monthlyLimit the
                        // user just set. One retry is enough — user edits
                        // aren't high-frequency.
                        existingByIdTable.setMonthlyLimit(monthlyLimit);
                        try {
                            budgetRepository.saveWithLock(existingByIdTable);
                        } catch (
                                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper
                                                .OptimisticLockException
                                        e) {
                            final BudgetTable fresh =
                                    budgetRepository
                                            .findById(normalizedId)
                                            .orElseThrow(
                                                    () ->
                                                            new AppException(
                                                                    ErrorCode.INVALID_INPUT,
                                                                    "Budget disappeared mid-update"));
                            fresh.setMonthlyLimit(monthlyLimit);
                            budgetRepository.saveWithLock(fresh);
                            existingByIdTable = fresh;
                        }
                        LOGGER.info(
                                "Budget with ID {} already exists for user {} and category {}. Updated and returning for idempotency.",
                                normalizedId,
                                user.getUserId(),
                                category);
                        return existingByIdTable;
                    } else {
                        // Budget exists but belongs to different user or category - throw exception
                        LOGGER.error(
                                "Budget with ID {} already exists but belongs to different user or category. User: {}, Category: {}",
                                normalizedId,
                                existingByIdTable.getUserId(),
                                existingByIdTable.getCategory());
                        throw new AppException(
                                ErrorCode.RECORD_ALREADY_EXISTS,
                                "Budget with ID already exists for different user or category");
                    }
                }
                // Set normalized ID
                budget.setBudgetId(normalizedId);
                LOGGER.debug(
                        "Using provided budget ID (normalized): {} -> {}", budgetId, normalizedId);
            } else {
                // Generate deterministic ID from user + category
                final String generatedId =
                        com.budgetbuddy.util.IdGenerator.generateBudgetId(
                                user.getUserId(), category);
                // CRITICAL FIX: Normalize generated ID to lowercase for consistency
                final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(generatedId);
                budget.setBudgetId(normalizedId);
                LOGGER.debug(
                        "Generated budget ID (normalized): {} from user: {} and category: {}",
                        normalizedId,
                        user.getUserId(),
                        category);
            }

            budget.setUserId(user.getUserId());
            budget.setCategory(category);
            budget.setMonthlyLimit(monthlyLimit);
            budget.setCurrentSpent(BigDecimal.ZERO);
            budget.setCurrencyCode(
                    currencyCode != null && !currencyCode.isBlank()
                            ? currencyCode
                            : (user.getPreferredCurrency() != null
                                            && !user.getPreferredCurrency().isEmpty()
                                    ? user.getPreferredCurrency()
                                    : "USD"));
            budget.setRolloverEnabled(rolloverEnabled != null ? rolloverEnabled : false);
            budget.setCarriedAmount(carriedAmount != null ? carriedAmount : BigDecimal.ZERO);
            budget.setGoalId(goalId);
            budget.setGoalAllocation(goalAllocation);
            budget.setPeriod(period != null && !period.isBlank() ? period : "monthly");
        }

        // Update current spent for current month
        try {
            final LocalDate startOfMonth = LocalDate.now().withDayOfMonth(1);
            final String startDateStr = startOfMonth.format(DATE_FORMATTER);
            final String endDateStr = LocalDate.now().format(DATE_FORMATTER);
            final List<TransactionTable> transactions =
                    transactionRepository.findByUserIdAndDateRange(
                            user.getUserId(), startDateStr, endDateStr);

            final BigDecimal currentSpent =
                    transactions.stream()
                            .filter(
                                    t ->
                                            t != null
                                                    && (category.equals(t.getCategoryPrimary())
                                                    || category.equals(
                                                    t.getCategoryDetailed())))
                            .map(TransactionTable::getAmount)
                            .filter(amount -> amount != null)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

            budget.setCurrentSpent(currentSpent);
        } catch (Exception e) {
            LOGGER.warn("Failed to calculate current spent for budget: {}", e.getMessage());
            // Continue with existing currentSpent value
        }

        // First write — version is null so saveWithLock conditions on
        // attribute_not_exists(version), which protects against two
        // simultaneous "create budget for user+category" requests creating
        // duplicate rows (without this, the second request would overwrite
        // the first's version and we'd silently lose the initial carryover/
        // rollover config).
        try {
            budgetRepository.saveWithLock(budget);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            // Someone else created the budget in parallel; fall back to
            // read-modify to apply our fields on top of theirs.
            final BudgetTable fresh =
                    budgetRepository
                            .findById(budget.getBudgetId())
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorCode.INVALID_INPUT,
                                                    "Budget missing after create race"));
            fresh.setMonthlyLimit(budget.getMonthlyLimit());
            fresh.setPeriod(budget.getPeriod());
            fresh.setRolloverEnabled(budget.getRolloverEnabled());
            fresh.setCarriedAmount(budget.getCarriedAmount());
            fresh.setGoalId(budget.getGoalId());
            fresh.setGoalAllocation(budget.getGoalAllocation());
            budgetRepository.saveWithLock(fresh);
            budget = fresh;
        }
        return budget;
    }

    /**
     * Create or update budget (backward compatibility - generates deterministic ID, no rollover, no
     * goalId)
     */
    public BudgetTable createOrUpdateBudget(
            final UserTable user, final String category, final BigDecimal monthlyLimit) {
        return createOrUpdateBudget(user, category, monthlyLimit, null, null, null, null);
    }

    /** Create or update budget (backward compatibility - generates deterministic ID, no goalId) */
    public BudgetTable createOrUpdateBudget(
            final UserTable user,
            final String category,
            final BigDecimal monthlyLimit,
            final String budgetId) {
        return createOrUpdateBudget(user, category, monthlyLimit, budgetId, null, null, null);
    }

    public List<BudgetTable> getBudgets(final UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        return budgetRepository.findByUserId(user.getUserId());
    }

    public BudgetTable getBudget(final UserTable user, final String budgetId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (budgetId == null || budgetId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Budget ID is required");
        }

        final BudgetTable budget =
                budgetRepository
                        .findById(budgetId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.BUDGET_NOT_FOUND, "Budget not found"));

        if (budget.getUserId() == null || !budget.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Budget does not belong to user");
        }

        return budget;
    }

    public void deleteBudget(final UserTable user, final String budgetId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (budgetId == null || budgetId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Budget ID is required");
        }

        final BudgetTable budget =
                budgetRepository
                        .findById(budgetId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.BUDGET_NOT_FOUND, "Budget not found"));

        if (budget.getUserId() == null || !budget.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Budget does not belong to user");
        }

        budgetRepository.delete(budgetId);
    }
}
