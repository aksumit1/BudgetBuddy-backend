package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
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
import java.util.UUID;

/**
 * Goal Service
 * Migrated to DynamoDB
 */
@Service
public class GoalService {

    private static final Logger logger = LoggerFactory.getLogger(GoalService.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final GoalRepository goalRepository;

    public GoalService(final GoalRepository goalRepository) {
        this.goalRepository = goalRepository;
    }

    /**
     * Create goal
     * @param goalId Optional goal ID from app. If provided and valid, use it for consistency.
     *               If not provided, generate deterministic ID from user + goal name.
     */
    public GoalTable createGoal(final UserTable user, final String name, final String description, final BigDecimal targetAmount, final LocalDate targetDate, final String goalType, final String goalId) {
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
            // Check if goal with this ID already exists
            Optional<GoalTable> existingById = goalRepository.findById(goalId);
            if (existingById.isPresent()) {
                throw new AppException(ErrorCode.RECORD_ALREADY_EXISTS, "Goal with ID " + goalId + " already exists");
            }
            goal.setGoalId(goalId);
            logger.debug("Using provided goal ID: {}", goalId);
        } else {
            // Generate deterministic ID from user + goal name
            goal.setGoalId(com.budgetbuddy.util.IdGenerator.generateGoalId(user.getUserId(), name.trim()));
            logger.debug("Generated goal ID: {} from user: {} and name: {}", 
                goal.getGoalId(), user.getUserId(), name);
        }
        
        goal.setUserId(user.getUserId());
        goal.setName(name.trim());
        goal.setDescription(description != null ? description.trim() : "");
        goal.setTargetAmount(targetAmount);
        goal.setTargetDate(targetDate.format(DATE_FORMATTER));
        goal.setGoalType(goalType);
        goal.setActive(true);
        goal.setCurrentAmount(BigDecimal.ZERO);
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
        return createGoal(user, name, description, targetAmount, targetDate, goalType, null);
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
        return goalRepository.findById(goalId)
                .orElseThrow(() -> new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found after update"));
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
