package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Goal Service */
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
public class GoalService {

    private static final String GOAL_ID_IS_REQUIRED = "Goal ID is required";

    private static final String GOAL_DISAPPEARED = "Goal disappeared";

    private static final String GOAL_DOES_NOT_BELONG_TO_USER = "Goal does not belong to user";

    private static final String GOAL_NOT_FOUND_1 = "Goal not found";

    private static final String USER_IS_REQUIRED = "User is required";

    private static final Logger LOGGER = LoggerFactory.getLogger(GoalService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final GoalRepository goalRepository;
    private final AccountRepository accountRepository;

    // Cross-flow audit fix: soft-delete needs to cascade to transactions + budgets so
    // we don't leave dangling goalId references. Setter-injected (not constructor) to
    // keep the existing constructor untouched and avoid a breaking change.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.budgetbuddy.repository.dynamodb.TransactionRepository transactionRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.budgetbuddy.repository.dynamodb.BudgetRepository budgetRepository;

    public GoalService(
            final GoalRepository goalRepository, final AccountRepository accountRepository) {
        this.goalRepository = goalRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Create goal
     *
     * @param goalId Optional goal ID from app. If provided and valid, use it for consistency. If
     *     not provided, generate deterministic ID from user + goal name.
     * @param currentAmount Optional initial current amount. If null or negative, defaults to 0.
     * @param accountIds Optional list of account IDs to associate with this goal.
     */
    public GoalTable createGoal(
            final UserTable user,
            final String name,
            final String description,
            final BigDecimal targetAmount,
            final LocalDate targetDate,
            final String goalType,
            final String goalId,
            final BigDecimal currentAmount,
            final List<String> accountIds) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (name == null || name.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal name is required");
        }
        if (targetAmount == null || targetAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Target amount must be positive");
        }
        if (targetDate == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Target date is required");
        }
        if (goalType == null || goalType.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal type is required");
        }

        final GoalTable goal = new GoalTable();

        // Use provided goalId if valid, otherwise generate deterministic ID
        if (goalId != null
                && !goalId.isEmpty()
                && com.budgetbuddy.util.IdGenerator.isValidUUID(goalId)) {
            // CRITICAL FIX: Normalize ID to lowercase before checking for existing
            // This ensures we check with the normalized ID that will be saved
            final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(goalId);

            // Check if goal with this ID already exists (using normalized ID)
            final Optional<GoalTable> existingById = goalRepository.findById(normalizedId);
            if (existingById.isPresent()) {
                GoalTable existing = existingById.get();
                // CRITICAL FIX: Verify the existing goal belongs to the same user
                if (!existing.getUserId().equals(user.getUserId())) {
                    // Goal exists but belongs to different user - throw exception
                    if (LOGGER.isErrorEnabled()) {
                        LOGGER.error(
                                "Goal with ID {} already exists but belongs to different user. User: {}, Existing User: {}",
                                normalizedId,
                                user.getUserId(),
                                existing.getUserId());
                    }
                    throw new AppException(
                            ErrorCode.RECORD_ALREADY_EXISTS,
                            "Goal with ID already exists for different user");
                }

                // CRITICAL FIX: Update existing goal if fields differ (upsert behavior)
                // Check if any updatable fields have changed
                final String formattedTargetDate = targetDate.format(DATE_FORMATTER);
                final BigDecimal newCurrentAmount =
                        currentAmount != null && currentAmount.compareTo(BigDecimal.ZERO) >= 0
                                ? currentAmount
                                : BigDecimal.ZERO;

                // CRITICAL FIX: Normalize and validate accountIds, then check if they've changed
                final List<String> normalizedAccountIds =
                        normalizeAndValidateAccountIds(accountIds, user.getUserId());
                final List<String> existingAccountIds =
                        existing.getAccountIds() != null
                                ? existing.getAccountIds()
                                : new java.util.ArrayList<>();
                final boolean accountIdsChanged =
                        !listsEqualIgnoreOrder(normalizedAccountIds, existingAccountIds);

                final boolean needsUpdate =
                        !existing.getName().equals(name.trim())
                                || !existing.getTargetAmount().equals(targetAmount)
                                || !existing.getTargetDate().equals(formattedTargetDate)
                                || !existing.getCurrentAmount().equals(newCurrentAmount)
                                || !existing.getGoalType().equals(goalType)
                                || accountIdsChanged
                                || (description != null
                                        && !existing.getDescription().equals(description.trim()))
                                || (description == null
                                        && existing.getDescription() != null
                                        && !existing.getDescription().isEmpty());

                if (needsUpdate) {
                    // Update existing goal with new values. Under lock so a
                    // concurrent budget→goal auto-flow (which bumps
                    // currentAmount) doesn't clobber the user's target-amount
                    // edit. One retry covers the common race; deeper conflict
                    // storms are rare and resolved on the next save attempt.
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(
                                "Goal with ID {} already exists. Updating fields for user {} with name {}.",
                                normalizedId,
                                user.getUserId(),
                                name);
                    }
                    existing.setName(name.trim());
                    existing.setDescription(description != null ? description.trim() : "");
                    existing.setTargetAmount(targetAmount);
                    existing.setTargetDate(formattedTargetDate);
                    existing.setGoalType(goalType);
                    existing.setCurrentAmount(newCurrentAmount);
                    existing.setAccountIds(new java.util.ArrayList<>(normalizedAccountIds));
                    existing.setUpdatedAt(Instant.now());
                    try {
                        goalRepository.saveWithLock(existing);
                    } catch (
                            com.budgetbuddy.repository.dynamodb.OptimisticLockHelper
                                            .OptimisticLockException
                                    e) {
                        final GoalTable fresh =
                                goalRepository
                                        .findById(normalizedId)
                                        .orElseThrow(
                                                () ->
                                                        new AppException(
                                                                ErrorCode.GOAL_NOT_FOUND,
                                                                "Goal disappeared during update"));
                        fresh.setName(existing.getName());
                        fresh.setDescription(existing.getDescription());
                        fresh.setTargetAmount(existing.getTargetAmount());
                        fresh.setTargetDate(existing.getTargetDate());
                        fresh.setGoalType(existing.getGoalType());
                        fresh.setAccountIds(existing.getAccountIds());
                        // Preserve the fresher currentAmount from the auto-flow
                        // race winner unless the user explicitly passed one in.
                        if (currentAmount != null
                                && currentAmount.compareTo(BigDecimal.ZERO) >= 0) {
                            fresh.setCurrentAmount(currentAmount);
                        }
                        fresh.setUpdatedAt(Instant.now());
                        goalRepository.saveWithLock(fresh);
                        existing = fresh;
                    }
                    return existing;
                } else {
                    // No changes - return existing (idempotent)
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug(
                                "Goal with ID {} already exists with same values for user {} with name {}. Returning existing for idempotency.",
                                normalizedId,
                                user.getUserId(),
                                name);
                    }
                    return existing;
                }
            }
            // Set normalized ID
            goal.setGoalId(normalizedId);
            LOGGER.debug("Using provided goal ID (normalized): {} -> {}", goalId, normalizedId);
        } else {
            // Generate deterministic ID from user + goal name
            final String generatedId =
                    com.budgetbuddy.util.IdGenerator.generateGoalId(user.getUserId(), name.trim());
            // CRITICAL FIX: Normalize generated ID to lowercase for consistency
            final String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(generatedId);
            goal.setGoalId(normalizedId);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                        "Generated goal ID (normalized): {} from user: {} and name: {}",
                        normalizedId,
                        user.getUserId(),
                        name);
            }
        }

        goal.setUserId(user.getUserId());
        goal.setName(name.trim());
        goal.setDescription(description != null ? description.trim() : "");
        goal.setTargetAmount(targetAmount);
        goal.setTargetDate(targetDate.format(DATE_FORMATTER));
        goal.setGoalType(goalType);
        goal.setActive(true);
        // Use currentAmount from request if provided and non-negative, otherwise default to 0
        if (currentAmount != null && currentAmount.compareTo(BigDecimal.ZERO) >= 0) {
            goal.setCurrentAmount(currentAmount);
        } else {
            goal.setCurrentAmount(BigDecimal.ZERO);
        }
        goal.setCompleted(false);
        goal.setAccountIds(
                normalizeAndValidateAccountIds(
                        accountIds,
                        user.getUserId())); // Normalize, validate, and deduplicate accountIds
        goal.setCompletedAt(null);
        goal.setRoundUpEnabled(false); // Round-up disabled by default
        goal.setCurrencyCode(
                user.getPreferredCurrency() != null && !user.getPreferredCurrency().isEmpty()
                        ? user.getPreferredCurrency()
                        : "USD");

        // Set timestamps
        final Instant now = Instant.now();
        goal.setCreatedAt(now);
        goal.setUpdatedAt(now);

        // First write under the lock (attribute_not_exists(version)) — if two
        // parallel creates slip through (idempotency key missing, client
        // double-tap), the second gets OptimisticLockException and we fall
        // through to a read of the winner's row for idempotent response.
        try {
            goalRepository.saveWithLock(goal);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            final GoalTable winner = goalRepository.findById(goal.getGoalId()).orElse(goal);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("Goal {} create race — returning existing row", winner.getGoalId());
            }
            return winner;
        }
        return goal;
    }

    /** Create goal (backward compatibility - generates deterministic ID) */
    public GoalTable createGoal(
            final UserTable user,
            final String name,
            final String description,
            final BigDecimal targetAmount,
            final LocalDate targetDate,
            final String goalType) {
        return createGoal(
                user, name, description, targetAmount, targetDate, goalType, null, null, null);
    }

    public List<GoalTable> getActiveGoals(final UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        return goalRepository.findByUserId(user.getUserId());
    }

    public GoalTable getGoal(final UserTable user, final String goalId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, GOAL_ID_IS_REQUIRED);
        }

        final GoalTable goal =
                goalRepository
                        .findById(goalId)
                        .orElseThrow(
                                () -> new AppException(ErrorCode.GOAL_NOT_FOUND, GOAL_NOT_FOUND_1));

        if (goal.getUserId() == null || !goal.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, GOAL_DOES_NOT_BELONG_TO_USER);
        }

        return goal;
    }

    /**
     * Update goal progress (cost-optimized: uses UpdateItem with increment) Note: Still requires
     * read for authorization check, but increment is atomic
     */
    public GoalTable updateGoalProgress(
            final UserTable user, final String goalId, final BigDecimal additionalAmount) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, GOAL_ID_IS_REQUIRED);
        }
        if (additionalAmount == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Additional amount is required");
        }

        // Authorization check (required for security)
        final GoalTable goal =
                goalRepository
                        .findById(goalId)
                        .orElseThrow(
                                () -> new AppException(ErrorCode.GOAL_NOT_FOUND, GOAL_NOT_FOUND_1));

        if (goal.getUserId() == null || !goal.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, GOAL_DOES_NOT_BELONG_TO_USER);
        }

        // Use optimized increment method (more efficient than read-then-write)
        goalRepository.incrementProgress(goalId, additionalAmount);

        // Return updated goal (read again to get latest value)
        // Note: In a high-performance scenario, we could calculate the new value
        // instead of reading, but this ensures consistency
        final GoalTable updatedGoal =
                goalRepository
                        .findById(goalId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.GOAL_NOT_FOUND,
                                                "Goal not found after update"));

        // Check if goal is now completed and update if needed
        checkAndMarkCompleted(updatedGoal);

        // Return the updated goal (may have been updated by checkAndMarkCompleted)
        return goalRepository
                .findById(goalId)
                .orElseThrow(
                        () ->
                                new AppException(
                                        ErrorCode.GOAL_NOT_FOUND,
                                        "Goal not found after completion check"));
    }

    /** Check if goal is completed (currentAmount >= targetAmount) and mark as completed if so */
    private void checkAndMarkCompleted(final GoalTable goal) {
        if (goal == null || goal.getTargetAmount() == null || goal.getCurrentAmount() == null) {
            return;
        }

        boolean isCompleted = goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0;
        boolean currentlyMarkedCompleted = goal.getCompleted() != null && goal.getCompleted();

        // Flip the completion flag under optimistic concurrency. A parallel
        // user contribution + Plaid auto-flow could otherwise race, with the
        // later writer clobbering the completion timestamp or resurrecting
        // a goal the user just unchecked. On conflict we retry once — the
        // completion decision is cheap and the re-read gets us the truth.
        if (isCompleted == currentlyMarkedCompleted) {
            return;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            if (isCompleted) {
                goal.setCompleted(true);
                goal.setCompletedAt(Instant.now());
            } else {
                goal.setCompleted(false);
                goal.setCompletedAt(null);
            }
            goal.setUpdatedAt(Instant.now());
            try {
                goalRepository.saveWithLock(goal);
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(
                            "Goal {} marked as {} (current={}, target={})",
                            goal.getGoalId(),
                            isCompleted ? "completed" : "not completed",
                            goal.getCurrentAmount(),
                            goal.getTargetAmount());
                }
                return;
            } catch (
                    com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                            e) {
                if (attempt == 1) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Goal {} completion flag update lost to concurrent writer after retry",
                                goal.getGoalId());
                    }
                    return;
                }
                // Re-read the latest row, re-evaluate, retry.
                final GoalTable refreshed = goalRepository.findById(goal.getGoalId()).orElse(null);
                if (refreshed == null) {
                    return;
                }
                goal.setVersion(refreshed.getVersion());
                goal.setCurrentAmount(refreshed.getCurrentAmount());
                isCompleted =
                        goal.getCurrentAmount() != null
                                && goal.getTargetAmount() != null
                                && goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0;
                currentlyMarkedCompleted =
                        refreshed.getCompleted() != null && refreshed.getCompleted();
                if (isCompleted == currentlyMarkedCompleted) {
                    return;
                }
            }
        }
    }

    /**
     * Flow 6 / O10 — manually mark a goal complete regardless of current progress. Used when the
     * user achieved the goal outside the app (e.g., transferred funds from a brokerage we don't
     * see). Sets currentAmount to targetAmount so the progress display reflects the user's intent.
     */
    public GoalTable manualMarkComplete(final UserTable user, final String goalId) {
        // Capture the moment the user requested completion — preserve this
        // timestamp through a retry rather than minting a new "now" on the
        // second attempt, so the audit trail reflects when the user actually
        // clicked Complete, not when a contention loop happened to win.
        final Instant requestedAt = Instant.now();

        GoalTable goal = getGoal(user, goalId);
        goal.setCompleted(true);
        goal.setCompletedAt(requestedAt);
        if (goal.getTargetAmount() != null) {
            goal.setCurrentAmount(goal.getTargetAmount());
        }
        goal.setLastMilestoneReached(100);
        goal.setUpdatedAt(requestedAt);
        // Under lock: if the budget→goal flow just credited $X, we still want
        // the "completed" decision to stick — but we want to preserve the
        // fresher currentAmount (user sees correct progress history). One
        // retry is enough for the common case.
        try {
            goalRepository.saveWithLock(goal);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            final GoalTable fresh =
                    goalRepository
                            .findById(goalId)
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorCode.GOAL_NOT_FOUND, GOAL_DISAPPEARED));
            fresh.setCompleted(true);
            // G-BUG-3: keep the original requestedAt, not a fresh Instant.now().
            // If the fresh row already carries an earlier completedAt (auto-recalc
            // raced us to "complete"), keep that earlier one so the audit log
            // doesn't move backwards. Otherwise stamp the user's request time.
            final Instant freshCompletedAt = fresh.getCompletedAt();
            if (freshCompletedAt == null || freshCompletedAt.isAfter(requestedAt)) {
                fresh.setCompletedAt(requestedAt);
            }
            if (fresh.getTargetAmount() != null
                    && (fresh.getCurrentAmount() == null
                            || fresh.getCurrentAmount().compareTo(fresh.getTargetAmount()) < 0)) {
                fresh.setCurrentAmount(fresh.getTargetAmount());
            }
            fresh.setLastMilestoneReached(100);
            fresh.setUpdatedAt(Instant.now());
            goalRepository.saveWithLock(fresh);
            goal = fresh;
        }
        LOGGER.info("Manual complete: goal {}", goalId);
        return goal;
    }

    /**
     * Flow 6 / O10 — reopen a completed goal. Clears completion flags and the last-milestone
     * bookmark so new milestones can fire again as the user progresses.
     */
    public GoalTable reopen(final UserTable user, final String goalId) {
        GoalTable goal = getGoal(user, goalId);
        goal.setCompleted(false);
        goal.setCompletedAt(null);
        goal.setLastMilestoneReached(null);
        goal.setUpdatedAt(Instant.now());
        try {
            goalRepository.saveWithLock(goal);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            final GoalTable fresh =
                    goalRepository
                            .findById(goalId)
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorCode.GOAL_NOT_FOUND, GOAL_DISAPPEARED));
            fresh.setCompleted(false);
            fresh.setCompletedAt(null);
            fresh.setLastMilestoneReached(null);
            fresh.setUpdatedAt(Instant.now());
            goalRepository.saveWithLock(fresh);
            goal = fresh;
        }
        LOGGER.info("Reopened goal {}", goalId);
        return goal;
    }

    /**
     * Associate accounts with a goal for goal-specific savings tracking After associating accounts,
     * recalculates currentAmount from account balances
     */
    public GoalTable associateAccounts(
            final UserTable user, final String goalId, final List<String> accountIds) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, GOAL_ID_IS_REQUIRED);
        }
        if (accountIds == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account IDs list is required");
        }

        GoalTable goal = getGoal(user, goalId);
        goal.setAccountIds(normalizeAndValidateAccountIds(accountIds, user.getUserId()));
        goal.setUpdatedAt(Instant.now());
        try {
            goalRepository.saveWithLock(goal);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            // Re-read, re-apply only the field this endpoint owns, retry.
            final GoalTable fresh =
                    goalRepository
                            .findById(goalId)
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorCode.GOAL_NOT_FOUND, GOAL_DISAPPEARED));
            fresh.setAccountIds(goal.getAccountIds());
            fresh.setUpdatedAt(Instant.now());
            goalRepository.saveWithLock(fresh);
            goal = fresh;
        }

        // CRITICAL: Recalculate currentAmount from account balances after associating accounts
        // This ensures goal progress reflects the actual account balances
        // Note: GoalProgressService is injected via constructor, but we need to check if it's
        // available
        // For now, we'll rely on the iOS side to call recalculateProgress endpoint
        // In a future enhancement, we could inject GoalProgressService here and call it directly

        return goal;
    }

    /**
     * Normalize and validate account IDs for a goal - Normalizes UUIDs to lowercase - Validates
     * UUID format - Validates accounts exist and belong to user - Removes duplicates - Returns
     * empty list if null/empty input
     *
     * @param accountIds Raw account IDs from request (may be null, empty, contain
     *     duplicates/invalid IDs)
     * @param userId User ID for ownership validation
     * @return Normalized, validated, deduplicated list of account IDs
     */
    private List<String> normalizeAndValidateAccountIds(
            final List<String> accountIds, final String userId) {
        if (accountIds == null || accountIds.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        final java.util.Set<String> normalizedIds =
                new java.util
                        .LinkedHashSet<>(); // LinkedHashSet preserves order and removes duplicates
        final List<String> invalidIds = new java.util.ArrayList<>();

        for (final String accountId : accountIds) {
            if (accountId == null || accountId.isBlank()) {
                continue; // Skip null/empty IDs
            }

            // Normalize UUID to lowercase
            final String normalizedId = accountId.trim().toLowerCase(Locale.ROOT);

            // Validate UUID format
            if (!com.budgetbuddy.util.IdGenerator.isValidUUID(normalizedId)) {
                invalidIds.add(accountId);
                LOGGER.warn("Invalid account ID format in goal accountIds: {}", accountId);
                continue; // Skip invalid UUIDs
            }

            // Validate account exists and belongs to user
            final Optional<AccountTable> account = accountRepository.findById(normalizedId);
            if (account.isEmpty()) {
                invalidIds.add(accountId);
                LOGGER.warn("Account not found for goal accountIds: {}", accountId);
                continue; // Skip non-existent accounts
            }

            final AccountTable accountTable = account.get();
            if (accountTable.getUserId() == null || !accountTable.getUserId().equals(userId)) {
                invalidIds.add(accountId);
                LOGGER.warn(
                        "Account {} does not belong to user {} in goal accountIds",
                        accountId,
                        userId);
                continue; // Skip accounts not owned by user
            }

            normalizedIds.add(normalizedId);
        }

        if (!invalidIds.isEmpty()) {
            LOGGER.warn("Some account IDs were invalid or not found, skipping: {}", invalidIds);
        }

        return new java.util.ArrayList<>(normalizedIds);
    }

    /**
     * Compare two lists for equality ignoring order (set-based comparison) Used to detect if
     * accountIds have changed regardless of order
     *
     * @param list1 First list
     * @param list2 Second list
     * @return true if lists contain the same elements (ignoring order and duplicates)
     */
    private boolean listsEqualIgnoreOrder(final List<String> list1, final List<String> list2) {
        if (list1 == null && list2 == null) {
            return true;
        }
        if (list1 == null || list2 == null) {
            return false;
        }
        if (list1.isEmpty() && list2.isEmpty()) {
            return true;
        }

        // Convert to sets for order-insensitive comparison
        final java.util.Set<String> set1 = new java.util.HashSet<>(list1);
        final java.util.Set<String> set2 = new java.util.HashSet<>(list2);

        return set1.equals(set2);
    }

    /**
     * Cross-flow audit fix: previously this method hard-deleted the goal row, which conflicted with
     * the iOS `GoalMutationService.softDelete` contract that adds a `deletedAt` stamp and expects
     * it to round-trip back to the client. The old hard delete also left orphan `goalId` pointers
     * on transactions and budgets.
     *
     * <p>New contract (matches iOS): 1. Stamp `deletedAt` on the goal (soft delete). 2. Cascade:
     * null out `goalId` on every tagged transaction. 3. Cascade: null out `goalId` +
     * `goalAllocation` on every linked budget.
     *
     * <p>Transactions and budgets are best-effort — we log warnings on failure but don't abort the
     * goal deletion, because stale references are recoverable on the next incremental sync but a
     * half-deleted goal is not.
     */
    public void deleteGoal(final UserTable user, final String goalId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, GOAL_ID_IS_REQUIRED);
        }

        GoalTable goal =
                goalRepository
                        .findById(goalId)
                        .orElseThrow(
                                () -> new AppException(ErrorCode.GOAL_NOT_FOUND, GOAL_NOT_FOUND_1));

        if (goal.getUserId() == null || !goal.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, GOAL_DOES_NOT_BELONG_TO_USER);
        }

        // 1. Soft-delete stamp. Under lock so a concurrent budget→goal flow
        // doesn't see a goal as still-live, credit it, and then lose both
        // writes (the delete retries, the credit is orphaned).
        //
        // G-RISK-4 idempotency: if a prior delete already stamped deletedAt
        // but cascades only partially completed, the client's retry must
        // not overwrite the original deletion timestamp — that timestamp
        // drives audit log + the "deleted N days ago" purge. Re-run the
        // cascades below regardless; they're already idempotent because
        // they filter to rows still pointing at this goalId.
        if (goal.getDeletedAt() == null) {
            goal.setDeletedAt(Instant.now());
            goal.setUpdatedAt(Instant.now());
            try {
                goalRepository.saveWithLock(goal);
            } catch (
                    com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                            e) {
                final GoalTable fresh =
                        goalRepository
                                .findById(goalId)
                                .orElseThrow(
                                        () ->
                                                new AppException(
                                                        ErrorCode.GOAL_NOT_FOUND,
                                                        "Goal disappeared mid-delete"));
                if (fresh.getDeletedAt() == null) {
                    fresh.setDeletedAt(Instant.now());
                    fresh.setUpdatedAt(Instant.now());
                    goalRepository.saveWithLock(fresh);
                }
                // If fresh already has deletedAt set, another caller won the
                // race — let their timestamp stand and proceed to cascades.
            }
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Soft-deleted goal {} for user {}", goalId, user.getUserId());
        }

        // 2. Cascade to transactions: null the goalId pointer on every row that tagged
        //    this goal. Uses findByUserIdAndGoalId which is the same GSI path the
        //    progress service uses, so it's cheap.
        if (transactionRepository != null) {
            try {
                final List<com.budgetbuddy.model.dynamodb.TransactionTable> tagged =
                        transactionRepository.findByUserIdAndGoalId(user.getUserId(), goalId);
                for (final var tx : tagged) {
                    if (tx == null) {
                        continue;
                    }
                    tx.setGoalId(null);
                    tx.setUpdatedAt(Instant.now());
                    transactionRepository.save(tx);
                }
                if (!tagged.isEmpty()) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info(
                                "Cascaded goal delete: cleared goalId on {} transactions",
                                tagged.size());
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Transaction cascade for goal {} partially failed: {}",
                            goalId,
                            e.getMessage());
                }
            }
        }

        // 3. Cascade to budgets: find every budget the user owns that references this
        //    goal and clear goalId + goalAllocation. Preserve everything else on the
        //    budget (limit, period, rollover, carriedAmount, etc.).
        if (budgetRepository != null) {
            try {
                final List<com.budgetbuddy.model.dynamodb.BudgetTable> all =
                        budgetRepository.findByUserId(user.getUserId());
                int cleared = 0;
                for (final var b : all) {
                    if (b == null || !goalId.equals(b.getGoalId())) {
                        continue;
                    }
                    b.setGoalId(null);
                    b.setGoalAllocation(null);
                    b.setUpdatedAt(Instant.now());
                    budgetRepository.save(b);
                    cleared++;
                }
                if (cleared > 0) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("Cascaded goal delete: cleared goalId on {} budgets", cleared);
                    }
                }
            } catch (Exception e) {
                if (LOGGER.isWarnEnabled()) {
                    LOGGER.warn(
                            "Budget cascade for goal {} partially failed: {}",
                            goalId,
                            e.getMessage());
                }
            }
        }
    }

    /**
     * G-OPP-3: undo for an iOS Undo-toast soft-delete. Clears the
     * deletedAt stamp under lock so the goal becomes live again. Does
     * NOT replay the transaction/budget cascade — those rows had their
     * goalId pointers nulled at delete time and re-linking is the user's
     * job (they should re-tag transactions intentionally). Idempotent:
     * restoring an already-live goal is a no-op.
     */
    public GoalTable restoreGoal(final UserTable user, final String goalId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, GOAL_ID_IS_REQUIRED);
        }
        final GoalTable goal =
                goalRepository
                        .findById(goalId)
                        .orElseThrow(
                                () -> new AppException(ErrorCode.GOAL_NOT_FOUND, GOAL_NOT_FOUND_1));
        if (goal.getUserId() == null || !goal.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, GOAL_DOES_NOT_BELONG_TO_USER);
        }
        if (goal.getDeletedAt() == null) {
            return goal; // already live
        }
        goal.setDeletedAt(null);
        goal.setUpdatedAt(Instant.now());
        try {
            return goalRepository.saveWithLock(goal);
        } catch (
                com.budgetbuddy.repository.dynamodb.OptimisticLockHelper.OptimisticLockException
                        e) {
            final GoalTable fresh =
                    goalRepository
                            .findById(goalId)
                            .orElseThrow(
                                    () ->
                                            new AppException(
                                                    ErrorCode.GOAL_NOT_FOUND, GOAL_DISAPPEARED));
            if (fresh.getDeletedAt() != null) {
                fresh.setDeletedAt(null);
                fresh.setUpdatedAt(Instant.now());
                return goalRepository.saveWithLock(fresh);
            }
            return fresh;
        }
    }
}
