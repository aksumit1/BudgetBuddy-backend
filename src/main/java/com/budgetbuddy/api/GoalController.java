package com.budgetbuddy.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.GoalProgressService;
import com.budgetbuddy.service.GoalService;
import com.budgetbuddy.service.UserService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Goal REST Controller
 *
 * <p>Thread-safe with proper error handling
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
        justification = "JSON DTO / DynamoDB entity getters expose lists by reference; "
                        + "the design is value-semantic and Jackson creates fresh instances; Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@RestController
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goalService;
    private final GoalProgressService goalProgressService;
    private final UserService userService;
    private final com.budgetbuddy.notification.DataChangeNotificationService
            dataChangeNotificationService;
    // Flow 6 / O7 — expose the recommendations engine that was previously dark.
    private final com.budgetbuddy.service.FinancialGoalsRecommendationService recommendationService;
    // Flow 7 / O4 — audit every goal mutation.
    private final com.budgetbuddy.compliance.MutationAuditInterceptor auditInterceptor;
    // Correctness: retry-safe POST — same pattern as TransactionController.
    private final com.budgetbuddy.service.correctness.IdempotencyService idempotencyService;

    public GoalController(
            final GoalService goalService,
            final GoalProgressService goalProgressService,
            final UserService userService,
            final com.budgetbuddy.notification.DataChangeNotificationService
                    dataChangeNotificationService,
            final com.budgetbuddy.service.FinancialGoalsRecommendationService recommendationService,
            final com.budgetbuddy.compliance.MutationAuditInterceptor auditInterceptor,
            final com.budgetbuddy.service.correctness.IdempotencyService idempotencyService) {
        this.goalService = goalService;
        this.goalProgressService = goalProgressService;
        this.userService = userService;
        this.dataChangeNotificationService = dataChangeNotificationService;
        this.recommendationService = recommendationService;
        this.auditInterceptor = auditInterceptor;
        this.idempotencyService = idempotencyService;
    }

    @GetMapping
    public ResponseEntity<List<GoalTable>> getGoals(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final List<GoalTable> goals = goalService.getActiveGoals(user);

        // TODO Check whether all goals belong to the customer

        return ResponseEntity.ok(goals);
    }

    /**
     * Flow 6 / O7 — recommendations endpoint. Delegates to {@link
     * com.budgetbuddy.service.FinancialGoalsRecommendationService} which was fully built but never
     * surfaced. iOS renders these on the goals empty state and in a "Suggested for you" block when
     * the user has < 5 goals.
     */
    @GetMapping("/recommendations")
    public ResponseEntity<
                    List<
                            com.budgetbuddy.service.FinancialGoalsRecommendationService
                                    .FinancialGoalRecommendation>>
            recommendations(@AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        return ResponseEntity.ok(recommendationService.getRecommendations(user.getUserId()));
    }

    /**
     * Flow 6 / O10 — manual "mark complete" toggle. Sets {@code completed=true}, stamps {@code
     * completedAt}, and pushes a GOAL_ACHIEVED notification. Distinct from the automatic completion
     * that fires when progress crosses 100 %; useful when a user paid off a goal externally (e.g.,
     * transferred from a brokerage we can't see).
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<GoalTable> markComplete(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        final GoalTable updated = goalService.manualMarkComplete(user, id);
        // Fire-and-forget push notification; SNS / device-token failures must not
        // fail the user-facing mark-complete request.
        try {
            dataChangeNotificationService.notifyGoalMilestoneReached(
                    user.getUserId(), updated.getGoalId(), updated.getName(), 100, true);
        } catch (@SuppressWarnings("PMD.AvoidCatchingGenericException") Exception e) {
            org.slf4j.LoggerFactory.getLogger(GoalController.class)
                    .debug(
                            "Goal-milestone notification failed (best-effort, request not aborted): {}",
                            e.getMessage());
        }
        auditInterceptor.goalChanged(user.getUserId(), id, "COMPLETE", "Manual mark complete");
        return ResponseEntity.ok(updated);
    }

    /**
     * Flow 6 / O10 — reopens a previously-completed goal. Clears completion flags and the
     * last-milestone bookmark so the 50/75/100 pushes can fire again.
     */
    @PostMapping("/{id}/reopen")
    public ResponseEntity<GoalTable> reopen(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));
        final GoalTable updated = goalService.reopen(user, id);
        auditInterceptor.goalChanged(user.getUserId(), id, "REOPEN", "Goal reopened");
        return ResponseEntity.ok(updated);
    }

    @PostMapping
    public ResponseEntity<GoalTable> createGoal(
            @AuthenticationPrincipal final UserDetails userDetails,
            @org.springframework.web.bind.annotation.RequestHeader(
                            value = "Idempotency-Key",
                            required = false) final String idempotencyKey,
            @Valid @RequestBody final CreateGoalRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal request is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        if (request.getTargetAmount() == null
                || request.getTargetAmount().compareTo(BigDecimal.ZERO) <= 0) {
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

        // TODO validate parameters

        // Retry-safe: client sends Idempotency-Key so a dropped response →
        // retry returns the original goalId instead of a second goal row.
        final UserTable finalUser = user;
        final String resolvedGoalId =
                idempotencyService.runOnce(
                        user.getUserId(),
                        idempotencyKey,
                        () ->
                                goalService
                                        .createGoal(
                                                finalUser,
                                                request.getName(),
                                                request.getDescription(),
                                                request.getTargetAmount(),
                                                request.getTargetDate(),
                                                request.getGoalType(),
                                                request.getGoalId(),
                                                request.getCurrentAmount(),
                                                request.getAccountIds())
                                        .getGoalId());
        final GoalTable goal = goalService.getGoal(user, resolvedGoalId);

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyGoalChanged(user.getUserId(), goal.getGoalId());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GoalController.class)
                    .warn(
                            "Failed to send data change notification for goal creation: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(goal);
    }

    @PutMapping("/{id}/progress")
    public ResponseEntity<GoalTable> updateProgress(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String id,
            @RequestBody final UpdateProgressRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        if (request == null || request.getAmount() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Progress amount is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final GoalTable goal = goalService.updateGoalProgress(user, id, request.getAmount());

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyGoalChanged(user.getUserId(), goal.getGoalId());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GoalController.class)
                    .warn(
                            "Failed to send data change notification for goal progress update: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        return ResponseEntity.ok(goal);
    }

    @PutMapping("/{id}/accounts")
    public ResponseEntity<GoalTable> associateAccounts(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String id,
            @RequestBody final AssociateAccountsRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        if (request == null || request.getAccountIds() == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Account IDs list is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final GoalTable goal = goalService.associateAccounts(user, id, request.getAccountIds());

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyGoalChanged(user.getUserId(), goal.getGoalId());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GoalController.class)
                    .warn(
                            "Failed to send data change notification for goal account association: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        return ResponseEntity.ok(goal);
    }

    @PostMapping("/{id}/recalculate")
    public ResponseEntity<GoalTable> recalculateProgress(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final GoalTable goal = goalProgressService.calculateAndUpdateProgress(user, id);

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyGoalChanged(user.getUserId(), goal.getGoalId());
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GoalController.class)
                    .warn(
                            "Failed to send data change notification for goal recalculation: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        return ResponseEntity.ok(goal);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGoal(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String id) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        if (id == null || id.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Goal ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        goalService.deleteGoal(user, id);

        // Send push notification for real-time sync on other devices
        try {
            dataChangeNotificationService.notifyGoalChanged(user.getUserId(), id);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(GoalController.class)
                    .warn(
                            "Failed to send data change notification for goal deletion: {}",
                            e.getMessage());
            // Don't fail the request if notification fails
        }

        // Audit trail (previously missing for delete).
        auditInterceptor.goalChanged(
                user.getUserId(), id, "DELETE", "Goal soft-deleted with cascade");
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
        private BigDecimal
                currentAmount; // Optional: Initial current amount (defaults to 0 if not provided)
        private java.util.List<String>
                accountIds; // Optional: Account IDs to associate with this goal

        // Getters and setters
        public String getGoalId() {
            return goalId;
        }

        public void setGoalId(final String goalId) {
            this.goalId = goalId;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }

        public BigDecimal getTargetAmount() {
            return targetAmount;
        }

        public void setTargetAmount(final BigDecimal targetAmount) {
            this.targetAmount = targetAmount;
        }

        public LocalDate getTargetDate() {
            return targetDate;
        }

        public void setTargetDate(final LocalDate targetDate) {
            this.targetDate = targetDate;
        }

        public String getGoalType() {
            return goalType;
        }

        public void setGoalType(final String goalType) {
            this.goalType = goalType;
        }

        public BigDecimal getCurrentAmount() {
            return currentAmount;
        }

        public void setCurrentAmount(final BigDecimal currentAmount) {
            this.currentAmount = currentAmount;
        }

        public java.util.List<String> getAccountIds() {
            return accountIds;
        }

        public void setAccountIds(final java.util.List<String> accountIds) {
            this.accountIds = accountIds;
        }
    }

    public static class UpdateProgressRequest {
        private BigDecimal amount;

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(final BigDecimal amount) {
            this.amount = amount;
        }
    }

    public static class AssociateAccountsRequest {
        private java.util.List<String> accountIds;

        public java.util.List<String> getAccountIds() {
            return accountIds;
        }

        public void setAccountIds(final java.util.List<String> accountIds) {
            this.accountIds = accountIds;
        }
    }
}
