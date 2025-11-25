package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.UserService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticatedPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Goal REST Controller
 * Migrated to DynamoDB
 * 
 * Thread-safe with proper error handling
 */
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goalService;
    private final UserService userService;

    public GoalController(GoalService goalService, UserService userService) {
        this.goalService = goalService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<GoalTable>> getGoals(@AuthenticatedPrincipal UserDetails userDetails) {
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
            @AuthenticatedPrincipal UserDetails userDetails,
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
                request.getGoalType()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(goal);
    }

    @PutMapping("/{id}/progress")
    public ResponseEntity<GoalTable> updateProgress(
            @AuthenticatedPrincipal UserDetails userDetails,
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(
            @AuthenticatedPrincipal UserDetails userDetails,
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
        private String name;
        private String description;
        private BigDecimal targetAmount;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate targetDate;
        private String goalType;

        // Getters and setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public BigDecimal getTargetAmount() { return targetAmount; }
        public void setTargetAmount(BigDecimal targetAmount) { this.targetAmount = targetAmount; }
        public LocalDate getTargetDate() { return targetDate; }
        public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
        public String getGoalType() { return goalType; }
        public void setGoalType(String goalType) { this.goalType = goalType; }
    }

    public static class UpdateProgressRequest {
        private BigDecimal amount;

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}
