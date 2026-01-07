package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Budget REST Controller
 *
 * Thread-safe with proper error handling
 */
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;
    private final UserService userService;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "Spring dependency injection - services are singleton beans safe to share")
    public BudgetController(final BudgetService budgetService, final UserService userService) {
        this.budgetService = budgetService;
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<List<BudgetTable>> getBudgets(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<BudgetTable> budgets = budgetService.getBudgets(user);
        return ResponseEntity.ok(budgets);
    }

    @PostMapping
    public ResponseEntity<BudgetTable> createOrUpdateBudget(
            @AuthenticationPrincipal UserDetails userDetails,
            @Valid @RequestBody CreateBudgetRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Budget request is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Allow zero budgets for zero-based budgeting support
        if (request.getMonthlyLimit() == null || request.getMonthlyLimit().compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Monthly limit must be non-negative");
        }

        if (request.getCategory() == null || request.getCategory().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }

        BudgetTable budget = budgetService.createOrUpdateBudget(
            user, 
            request.getCategory(), 
            request.getMonthlyLimit(),
            request.getBudgetId(), // Pass optional budget ID from app
            request.getRolloverEnabled(), // Pass optional rollover enabled flag
            request.getCarriedAmount(), // Pass optional carried amount
            request.getGoalId() // Pass optional goal ID
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(budget);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBudget(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Budget ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        budgetService.deleteBudget(user, id);
        return ResponseEntity.noContent().build();
    }

    public static class CreateBudgetRequest {
        private String budgetId; // Optional: ID from app for consistency
        private String category;
        private BigDecimal monthlyLimit;
        private Boolean rolloverEnabled; // Optional: Whether budget rollover/carryover is enabled
        private BigDecimal carriedAmount; // Optional: Amount carried from previous month
        private String goalId; // Optional: ID of the goal this budget is linked to

        public String getBudgetId() { return budgetId; }
        public void setBudgetId(final String budgetId) { this.budgetId = budgetId; }
        public String getCategory() { return category; }
        public void setCategory(final String category) { this.category = category; }
        public BigDecimal getMonthlyLimit() { return monthlyLimit; }
        public void setMonthlyLimit(final BigDecimal monthlyLimit) { this.monthlyLimit = monthlyLimit; }
        public Boolean getRolloverEnabled() { return rolloverEnabled; }
        public void setRolloverEnabled(final Boolean rolloverEnabled) { this.rolloverEnabled = rolloverEnabled; }
        public BigDecimal getCarriedAmount() { return carriedAmount; }
        public void setCarriedAmount(final BigDecimal carriedAmount) { this.carriedAmount = carriedAmount; }
        public String getGoalId() { return goalId; }
        public void setGoalId(final String goalId) { this.goalId = goalId; }
    }
}
