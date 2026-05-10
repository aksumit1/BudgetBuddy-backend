package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.UserService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Budget REST Controller
 *
 * <p>Thread-safe with proper error handling
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@RestController
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgetService;
    private final UserService userService;
    private final com.budgetbuddy.notification.DataChangeNotificationService
            dataChangeNotificationService;
    private final com.budgetbuddy.service.BudgetSummaryService budgetSummaryService;
    // Flow 7 / O4 — route mutations through the audit interceptor.
    private final com.budgetbuddy.compliance.MutationAuditInterceptor auditInterceptor;
    // Correctness: retry-safe POST so a dropped network response doesn't
    // create a second identical budget row on client retry.
    private final com.budgetbuddy.service.correctness.IdempotencyService idempotencyService;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "Spring dependency injection - services are singleton beans safe to share")
    public BudgetController(
            final BudgetService budgetService,
            final UserService userService,
            final com.budgetbuddy.notification.DataChangeNotificationService
                    dataChangeNotificationService,
            final com.budgetbuddy.service.BudgetSummaryService budgetSummaryService,
            final com.budgetbuddy.compliance.MutationAuditInterceptor auditInterceptor,
            final com.budgetbuddy.service.correctness.IdempotencyService idempotencyService) {
        this.budgetService = budgetService;
        this.userService = userService;
        this.dataChangeNotificationService = dataChangeNotificationService;
        this.budgetSummaryService = budgetSummaryService;
        this.auditInterceptor = auditInterceptor;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public ResponseEntity<List<BudgetTable>> getBudgets(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final List<BudgetTable> budgets = budgetService.getBudgets(user);

        // TO DO: Check for each account in the repository for valid User Id, otherwise remove it

        return ResponseEntity.ok(budgets);
    }

    /**
     * Flow 5 / O12 — server-computed per-budget summary. Clients no longer need to re-implement
     * {@code BudgetEngine.buildBudgetSummaries}; they can just consume this. Keeps the math in one
     * place so future clients (web, watch, CLI) stay consistent.
     */
    @GetMapping("/summary")
    public ResponseEntity<List<com.budgetbuddy.service.BudgetSummaryService.BudgetSummaryDto>>
            getBudgetSummaries(@AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        return ResponseEntity.ok(budgetSummaryService.buildSummaries(user));
    }

    @PostMapping
    public ResponseEntity<BudgetTable> createOrUpdateBudget(
            @AuthenticationPrincipal final UserDetails userDetails,
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = "Idempotency-Key",
                            required = false) final String idempotencyKey,
            @Valid @RequestBody final CreateBudgetRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Budget request is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // Allow zero budgets for zero-based budgeting support
        if (request.getMonthlyLimit() == null
                || request.getMonthlyLimit().compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Monthly limit must be non-negative");
        }

        if (request.getCategory() == null || request.getCategory().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Category is required");
        }

        // TO DO: Validate the fields

        // Retry-safe: client sends Idempotency-Key per intended create. A
        // dropped network response → second request with same key returns
        // the first call's budgetId instead of creating a duplicate row.
        final UserTable finalUser = user;
        final String resolvedId =
                idempotencyService.runOnce(
                        user.getUserId(),
                        idempotencyKey,
                        () ->
                                budgetService
                                        .createOrUpdateBudget(
                                                finalUser,
                                                request.getCategory(),
                                                request.getMonthlyLimit(),
                                                request.getBudgetId(),
                                                request.getRolloverEnabled(),
                                                request.getCarriedAmount(),
                                                request.getGoalId(),
                                                request.getGoalAllocation(),
                                                request.getPeriod(),
                                                request.getCurrencyCode())
                                        .getBudgetId());
        final BudgetTable budget = budgetService.getBudget(user, resolvedId);

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyBudgetChanged(
                    user.getUserId(), budget.getBudgetId());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BudgetController.class)
                    .warn(
                            "Failed to send data change notification for budget creation/update: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        auditInterceptor.budgetChanged(
                user.getUserId(),
                budget.getBudgetId(),
                "UPDATE",
                String.format(
                        "category=%s limit=%s period=%s",
                        budget.getCategory(), budget.getMonthlyLimit(), budget.getPeriod()));

        return ResponseEntity.status(HttpStatus.CREATED).body(budget);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBudget(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Budget ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        // TO DO: Check if the budget and User ID matches

        budgetService.deleteBudget(user, id);

        try {
            dataChangeNotificationService.notifyBudgetChanged(user.getUserId(), id);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(BudgetController.class)
                    .warn(
                            "Failed to send data change notification for budget deletion: {}",
                            e.getMessage());
        }
        auditInterceptor.budgetChanged(user.getUserId(), id, "DELETE", "Budget deleted");
        return ResponseEntity.noContent().build();
    }

    public static class CreateBudgetRequest {
        private String budgetId;
        private String category;
        private BigDecimal monthlyLimit;
        private Boolean rolloverEnabled;
        private BigDecimal carriedAmount;
        private String goalId;

        /** Flow 5 user ask: how much of the limit funds the linked goal each period. */
        private BigDecimal goalAllocation;

        /** Flow 5 / O3: "weekly" | "biweekly" | "monthly". */
        private String period;

        /** Flow 5 / O1: explicit currency from the client. */
        private String currencyCode;

        public String getBudgetId() {
            return budgetId;
        }

        public void setBudgetId(final String budgetId) {
            this.budgetId = budgetId;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(final String category) {
            this.category = category;
        }

        public BigDecimal getMonthlyLimit() {
            return monthlyLimit;
        }

        public void setMonthlyLimit(final BigDecimal monthlyLimit) {
            this.monthlyLimit = monthlyLimit;
        }

        public Boolean getRolloverEnabled() {
            return rolloverEnabled;
        }

        public void setRolloverEnabled(final Boolean rolloverEnabled) {
            this.rolloverEnabled = rolloverEnabled;
        }

        public BigDecimal getCarriedAmount() {
            return carriedAmount;
        }

        public void setCarriedAmount(final BigDecimal carriedAmount) {
            this.carriedAmount = carriedAmount;
        }

        public String getGoalId() {
            return goalId;
        }

        public void setGoalId(final String goalId) {
            this.goalId = goalId;
        }

        public BigDecimal getGoalAllocation() {
            return goalAllocation;
        }

        public void setGoalAllocation(final BigDecimal goalAllocation) {
            this.goalAllocation = goalAllocation;
        }

        public String getPeriod() {
            return period;
        }

        public void setPeriod(final String period) {
            this.period = period;
        }

        public String getCurrencyCode() {
            return currencyCode;
        }

        public void setCurrencyCode(final String currencyCode) {
            this.currencyCode = currencyCode;
        }
    }
}
