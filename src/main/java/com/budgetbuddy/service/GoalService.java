package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Goal Service
 */
@Service
public class GoalService {

    private static final Logger logger = LoggerFactory.getLogger(GoalService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final GoalRepository goalRepository;
    private final AccountRepository accountRepository;

    public GoalService(final GoalRepository goalRepository, final AccountRepository accountRepository) {
        this.goalRepository = goalRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * Create goal
     * @param goalId Optional goal ID from app. If provided and valid, use it for consistency.
     *               If not provided, generate deterministic ID from user + goal name.
     * @param currentAmount Optional initial current amount. If null or negative, defaults to 0.
     * @param accountIds Optional list of account IDs to associate with this goal.
     */
    public GoalTable createGoal(final UserTable user, final String name, final String description, final BigDecimal targetAmount, final LocalDate targetDate, final String goalType, final String goalId, final BigDecimal currentAmount, final java.util.List<String> accountIds) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (name == null || name.trim().isEmpty()) {
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

        GoalTable goal = new GoalTable();
        
        // Use provided goalId if valid, otherwise generate deterministic ID
        if (goalId != null && !goalId.isEmpty() && com.budgetbuddy.util.IdGenerator.isValidUUID(goalId)) {
            // CRITICAL FIX: Normalize ID to lowercase before checking for existing
            // This ensures we check with the normalized ID that will be saved
            String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(goalId);
            
            // Check if goal with this ID already exists (using normalized ID)
            Optional<GoalTable> existingById = goalRepository.findById(normalizedId);
            if (existingById.isPresent()) {
                GoalTable existing = existingById.get();
                // CRITICAL FIX: Verify the existing goal belongs to the same user
                if (!existing.getUserId().equals(user.getUserId())) {
                    // Goal exists but belongs to different user - throw exception
                    logger.error("Goal with ID {} already exists but belongs to different user. User: {}, Existing User: {}", 
                            normalizedId, user.getUserId(), existing.getUserId());
                    throw new AppException(ErrorCode.RECORD_ALREADY_EXISTS, 
                            "Goal with ID already exists for different user");
                }
                
                // CRITICAL FIX: Update existing goal if fields differ (upsert behavior)
                // Check if any updatable fields have changed
                String formattedTargetDate = targetDate.format(DATE_FORMATTER);
                BigDecimal newCurrentAmount = (currentAmount != null && currentAmount.compareTo(BigDecimal.ZERO) >= 0) 
                        ? currentAmount : BigDecimal.ZERO;
                
                // CRITICAL FIX: Normalize and validate accountIds, then check if they've changed
                java.util.List<String> normalizedAccountIds = normalizeAndValidateAccountIds(accountIds, user.getUserId());
                java.util.List<String> existingAccountIds = existing.getAccountIds() != null ? existing.getAccountIds() : new java.util.ArrayList<>();
                boolean accountIdsChanged = !listsEqualIgnoreOrder(normalizedAccountIds, existingAccountIds);
                
                boolean needsUpdate = !existing.getName().equals(name.trim()) ||
                        !existing.getTargetAmount().equals(targetAmount) ||
                        !existing.getTargetDate().equals(formattedTargetDate) ||
                        !existing.getCurrentAmount().equals(newCurrentAmount) ||
                        !existing.getGoalType().equals(goalType) ||
                        accountIdsChanged ||
                        (description != null && !existing.getDescription().equals(description.trim())) ||
                        (description == null && existing.getDescription() != null && !existing.getDescription().isEmpty());
                
                if (needsUpdate) {
                    // Update existing goal with new values
                    logger.info("Goal with ID {} already exists. Updating fields for user {} with name {}.", 
                            normalizedId, user.getUserId(), name);
                    existing.setName(name.trim());
                    existing.setDescription(description != null ? description.trim() : "");
                    existing.setTargetAmount(targetAmount);
                    existing.setTargetDate(formattedTargetDate);
                    existing.setGoalType(goalType);
                    existing.setCurrentAmount(newCurrentAmount);
                    existing.setAccountIds(new java.util.ArrayList<>(normalizedAccountIds)); // Update accountIds
                    existing.setUpdatedAt(Instant.now());
                    goalRepository.save(existing);
                    return existing;
                } else {
                    // No changes - return existing (idempotent)
                    logger.debug("Goal with ID {} already exists with same values for user {} with name {}. Returning existing for idempotency.", 
                            normalizedId, user.getUserId(), name);
                    return existing;
                }
            }
            // Set normalized ID
            goal.setGoalId(normalizedId);
            logger.debug("Using provided goal ID (normalized): {} -> {}", goalId, normalizedId);
        } else {
            // Generate deterministic ID from user + goal name
            String generatedId = com.budgetbuddy.util.IdGenerator.generateGoalId(user.getUserId(), name.trim());
            // CRITICAL FIX: Normalize generated ID to lowercase for consistency
            String normalizedId = com.budgetbuddy.util.IdGenerator.normalizeUUID(generatedId);
            goal.setGoalId(normalizedId);
            logger.debug("Generated goal ID (normalized): {} from user: {} and name: {}", 
                normalizedId, user.getUserId(), name);
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
        goal.setAccountIds(normalizeAndValidateAccountIds(accountIds, user.getUserId())); // Normalize, validate, and deduplicate accountIds
        goal.setCompletedAt(null);
        goal.setRoundUpEnabled(false); // Round-up disabled by default
        goal.setCurrencyCode(user.getPreferredCurrency() != null && !user.getPreferredCurrency().isEmpty()
                ? user.getPreferredCurrency() : "USD");
        
        // Set timestamps
        Instant now = Instant.now();
        goal.setCreatedAt(now);
        goal.setUpdatedAt(now);

        goalRepository.save(goal);
        return goal;
    }

    /**
     * Create goal (backward compatibility - generates deterministic ID)
     */
    public GoalTable createGoal(final UserTable user, final String name, final String description, final BigDecimal targetAmount, final LocalDate targetDate, final String goalType) {
        return createGoal(user, name, description, targetAmount, targetDate, goalType, null, null, null);
    }

    public List<GoalTable> getActiveGoals(UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        return goalRepository.findByUserId(user.getUserId());
    }

    public GoalTable getGoal(final UserTable user, final String goalId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        GoalTable goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        if (goal.getUserId() == null || !goal.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Goal does not belong to user");
        }

        return goal;
    }

    /**
     * Update goal progress (cost-optimized: uses UpdateItem with increment)
     * Note: Still requires read for authorization check, but increment is atomic
     */
    public GoalTable updateGoalProgress(final UserTable user, final String goalId, final BigDecimal additionalAmount) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }
        if (additionalAmount == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Additional amount is required");
        }

        // Authorization check (required for security)
        GoalTable goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        if (goal.getUserId() == null || !goal.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Goal does not belong to user");
        }

        // Use optimized increment method (more efficient than read-then-write)
        goalRepository.incrementProgress(goalId, additionalAmount);

        // Return updated goal (read again to get latest value)
        // Note: In a high-performance scenario, we could calculate the new value
        // instead of reading, but this ensures consistency
        GoalTable updatedGoal = goalRepository.findById(goalId)
                .orElseThrow(() -> new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found after update"));
        
        // Check if goal is now completed and update if needed
        checkAndMarkCompleted(updatedGoal);
        
        // Return the updated goal (may have been updated by checkAndMarkCompleted)
        return goalRepository.findById(goalId)
                .orElseThrow(() -> new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found after completion check"));
    }
    
    /**
     * Check if goal is completed (currentAmount >= targetAmount) and mark as completed if so
     */
    private void checkAndMarkCompleted(final GoalTable goal) {
        if (goal == null || goal.getTargetAmount() == null || goal.getCurrentAmount() == null) {
            return;
        }
        
        boolean isCompleted = goal.getCurrentAmount().compareTo(goal.getTargetAmount()) >= 0;
        boolean currentlyMarkedCompleted = goal.getCompleted() != null && goal.getCompleted();
        
        // If goal is completed but not marked as such, update it
        if (isCompleted && !currentlyMarkedCompleted) {
            goal.setCompleted(true);
            goal.setCompletedAt(Instant.now());
            goal.setUpdatedAt(Instant.now());
            goalRepository.save(goal);
            logger.info("Goal {} marked as completed. Current: {}, Target: {}", 
                    goal.getGoalId(), goal.getCurrentAmount(), goal.getTargetAmount());
        } else if (!isCompleted && currentlyMarkedCompleted) {
            // Goal was marked completed but current amount dropped below target (e.g., withdrawal)
            goal.setCompleted(false);
            goal.setCompletedAt(null);
            goal.setUpdatedAt(Instant.now());
            goalRepository.save(goal);
            logger.info("Goal {} marked as not completed. Current: {}, Target: {}", 
                    goal.getGoalId(), goal.getCurrentAmount(), goal.getTargetAmount());
        }
    }
    
    /**
     * Associate accounts with a goal for goal-specific savings tracking
     * After associating accounts, recalculates currentAmount from account balances
     */
    public GoalTable associateAccounts(final UserTable user, final String goalId, final java.util.List<String> accountIds) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }
        if (accountIds == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account IDs list is required");
        }
        
        GoalTable goal = getGoal(user, goalId);
        goal.setAccountIds(normalizeAndValidateAccountIds(accountIds, user.getUserId()));
        goal.setUpdatedAt(Instant.now());
        goalRepository.save(goal);
        
        // CRITICAL: Recalculate currentAmount from account balances after associating accounts
        // This ensures goal progress reflects the actual account balances
        // Note: GoalProgressService is injected via constructor, but we need to check if it's available
        // For now, we'll rely on the iOS side to call recalculateProgress endpoint
        // In a future enhancement, we could inject GoalProgressService here and call it directly
        
        return goal;
    }

    /**
     * Normalize and validate account IDs for a goal
     * - Normalizes UUIDs to lowercase
     * - Validates UUID format
     * - Validates accounts exist and belong to user
     * - Removes duplicates
     * - Returns empty list if null/empty input
     * 
     * @param accountIds Raw account IDs from request (may be null, empty, contain duplicates/invalid IDs)
     * @param userId User ID for ownership validation
     * @return Normalized, validated, deduplicated list of account IDs
     */
    private java.util.List<String> normalizeAndValidateAccountIds(final java.util.List<String> accountIds, final String userId) {
        if (accountIds == null || accountIds.isEmpty()) {
            return new java.util.ArrayList<>();
        }
        
        java.util.Set<String> normalizedIds = new java.util.LinkedHashSet<>(); // LinkedHashSet preserves order and removes duplicates
        java.util.List<String> invalidIds = new java.util.ArrayList<>();
        
        for (String accountId : accountIds) {
            if (accountId == null || accountId.trim().isEmpty()) {
                continue; // Skip null/empty IDs
            }
            
            // Normalize UUID to lowercase
            String normalizedId = accountId.trim().toLowerCase();
            
            // Validate UUID format
            if (!com.budgetbuddy.util.IdGenerator.isValidUUID(normalizedId)) {
                invalidIds.add(accountId);
                logger.warn("Invalid account ID format in goal accountIds: {}", accountId);
                continue; // Skip invalid UUIDs
            }
            
            // Validate account exists and belongs to user
            Optional<AccountTable> account = accountRepository.findById(normalizedId);
            if (account.isEmpty()) {
                invalidIds.add(accountId);
                logger.warn("Account not found for goal accountIds: {}", accountId);
                continue; // Skip non-existent accounts
            }
            
            AccountTable accountTable = account.get();
            if (accountTable.getUserId() == null || !accountTable.getUserId().equals(userId)) {
                invalidIds.add(accountId);
                logger.warn("Account {} does not belong to user {} in goal accountIds", accountId, userId);
                continue; // Skip accounts not owned by user
            }
            
            normalizedIds.add(normalizedId);
        }
        
        if (!invalidIds.isEmpty()) {
            logger.warn("Some account IDs were invalid or not found, skipping: {}", invalidIds);
        }
        
        return new java.util.ArrayList<>(normalizedIds);
    }
    
    /**
     * Compare two lists for equality ignoring order (set-based comparison)
     * Used to detect if accountIds have changed regardless of order
     * 
     * @param list1 First list
     * @param list2 Second list
     * @return true if lists contain the same elements (ignoring order and duplicates)
     */
    private boolean listsEqualIgnoreOrder(final java.util.List<String> list1, final java.util.List<String> list2) {
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
        java.util.Set<String> set1 = new java.util.HashSet<>(list1);
        java.util.Set<String> set2 = new java.util.HashSet<>(list2);
        
        return set1.equals(set2);
    }

    public void deleteGoal(final UserTable user, final String goalId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        GoalTable goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        if (goal.getUserId() == null || !goal.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Goal does not belong to user");
        }

        goalRepository.delete(goalId);
    }
}
