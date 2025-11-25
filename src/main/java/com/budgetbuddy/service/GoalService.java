package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Goal Service
 * Migrated to DynamoDB
 */
@Service
public class GoalService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final GoalRepository goalRepository;

    public GoalService(final GoalRepository goalRepository) {
        this.goalRepository = goalRepository;
    }

    public GoalTable createGoal((final UserTable user, final String name, final String description, final BigDecimal targetAmount, final LocalDate targetDate, final String goalType) {
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
        goal.setGoalId(UUID.randomUUID().toString());
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

        goalRepository.save(goal);
        return goal;
    }

    public List<GoalTable> getActiveGoals(UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        return goalRepository.findByUserId(user.getUserId());
    }

    public GoalTable getGoal((final UserTable user, final String goalId) {
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

    public GoalTable updateGoalProgress((final UserTable user, final String goalId, final BigDecimal additionalAmount) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (goalId == null || goalId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }
        if (additionalAmount == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Additional amount is required");
        }

        GoalTable goal = goalRepository.findById(goalId)
                .orElseThrow(() -> new AppException(ErrorCode.GOAL_NOT_FOUND, "Goal not found"));

        if (goal.getUserId() == null || !goal.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Goal does not belong to user");
        }

        BigDecimal currentAmount = goal.getCurrentAmount() != null ? goal.getCurrentAmount() : BigDecimal.ZERO;
        goal.setCurrentAmount(currentAmount.add(additionalAmount));
        goalRepository.save(goal);
        return goal;
    }

    public void deleteGoal((final UserTable user, final String goalId) {
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
