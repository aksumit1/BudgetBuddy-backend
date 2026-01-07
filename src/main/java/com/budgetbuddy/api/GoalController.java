package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.GoalProgressService;
import com.budgetbuddy.service.UserService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Goal REST Controller
 *
 * Thread-safe with proper error handling
 */
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goalService;
    private final GoalProgressService goalProgressService;
    private final UserService userService;

    public GoalController(final GoalService goalService, final GoalProgressService goalProgressService, final UserService userService) {
        this.goalService = goalService;
        this.goalProgressService = goalProgressService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<GoalTable>> getGoals(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<GoalTable> goals = goalService.getActiveGoals(user);
        return ResponseEntity.ok(goals);
    }

    @PostMapping
    public ResponseEntity<GoalTable> createGoal(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateGoalRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal request is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (request.getTargetAmount() == null || request.getTargetAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Target amount must be positive");
        }

        if (request.getTargetDate() == null || request.getTargetDate().isBefore(LocalDate.now())) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Target date must be in the future");
        }

        if (request.getName() == null || request.getName().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal name is required");
        }

        if (request.getGoalType() == null || request.getGoalType().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal type is required");
        }

        GoalTable goal = goalService.createGoal(
                user,
                request.getName(),
                request.getDescription(),
                request.getTargetAmount(),
                request.getTargetDate(),
                request.getGoalType(),
                request.getGoalId(), // Pass optional goal ID from app
                request.getCurrentAmount(), // Pass optional current amount (defaults to 0 if null)
                request.getAccountIds() // Pass optional account IDs (will be set/updated in createGoal)
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(goal);
    }

    @PutMapping("/{id}/progress")
    public ResponseEntity<GoalTable> updateProgress(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id,
            @RequestBody UpdateProgressRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        if (request == null || request.getAmount() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Progress amount is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        GoalTable goal = goalService.updateGoalProgress(user, id, request.getAmount());
        return ResponseEntity.ok(goal);
    }

    @PutMapping("/{id}/accounts")
    public ResponseEntity<GoalTable> associateAccounts(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id,
            @RequestBody AssociateAccountsRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        if (request == null || request.getAccountIds() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account IDs list is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        GoalTable goal = goalService.associateAccounts(user, id, request.getAccountIds());
        return ResponseEntity.ok(goal);
    }

    @PostMapping("/{id}/recalculate")
    public ResponseEntity<GoalTable> recalculateProgress(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        GoalTable goal = goalProgressService.calculateAndUpdateProgress(user, id);
        return ResponseEntity.ok(goal);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        goalService.deleteGoal(user, id);
        return ResponseEntity.noContent().build();
    }

    public static class CreateGoalRequest {
        private String goalId; // Optional: ID from app for consistency
        private String name;
        private String description;
        private BigDecimal targetAmount;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate targetDate;
        private String goalType;
        private BigDecimal currentAmount; // Optional: Initial current amount (defaults to 0 if not provided)
        private java.util.List<String> accountIds; // Optional: Account IDs to associate with this goal

        // Getters and setters
        public String getGoalId() { return goalId; }
        public void setGoalId(final String goalId) { this.goalId = goalId; }
        public String getName() { return name; }
        public void setName(final String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(final String description) { this.description = description; }
        public BigDecimal getTargetAmount() { return targetAmount; }
        public void setTargetAmount(final BigDecimal targetAmount) { this.targetAmount = targetAmount; }
        public LocalDate getTargetDate() { return targetDate; }
        public void setTargetDate(final LocalDate targetDate) { this.targetDate = targetDate; }
        public String getGoalType() { return goalType; }
        public void setGoalType(final String goalType) { this.goalType = goalType; }
        public BigDecimal getCurrentAmount() { return currentAmount; }
        public void setCurrentAmount(final BigDecimal currentAmount) { this.currentAmount = currentAmount; }
        public java.util.List<String> getAccountIds() { return accountIds; }
        public void setAccountIds(final java.util.List<String> accountIds) { this.accountIds = accountIds; }
    }

    public static class UpdateProgressRequest {
        private BigDecimal amount;

        public BigDecimal getAmount() { return amount; }
        public void setAmount(final BigDecimal amount) { this.amount = amount; }
    }

    public static class AssociateAccountsRequest {
        private java.util.List<String> accountIds;

        public java.util.List<String> getAccountIds() { return accountIds; }
        public void setAccountIds(final java.util.List<String> accountIds) { this.accountIds = accountIds; }
    }
}
