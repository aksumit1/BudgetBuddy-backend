package com.budgetbuddy.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionActionService;
import com.budgetbuddy.service.UserService;
import java.util.List;
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

/** REST Controller for Transaction Actions/Reminders */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@RestController
@RequestMapping("/api/transactions")
public class TransactionActionController {

    private final TransactionActionService actionService;
    private final UserService userService;

    public TransactionActionController(
            final TransactionActionService actionService, final UserService userService) {
        this.actionService = actionService;
        this.userService = userService;
    }

    @GetMapping("/{transactionId}/actions")
    public ResponseEntity<List<TransactionActionTable>> getActions(
            @AuthenticationPrincipal final UserDetails userDetails, @PathVariable final String transactionId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final List<TransactionActionTable> actions =
                actionService.getActionsByTransactionId(user, transactionId);
        return ResponseEntity.ok(actions);
    }

    @PostMapping("/{transactionId}/actions")
    public ResponseEntity<TransactionActionTable> createAction(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable("transactionId") final String transactionId,
            @RequestBody final CreateActionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        if (request == null || request.getTitle() == null || request.getTitle().isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Title is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final TransactionActionTable action =
                actionService.createAction(
                        user,
                        transactionId,
                        request.getTitle(),
                        request.getDescription(),
                        request.getDueDate(),
                        request.getReminderDate(),
                        request.getPriority(),
                        request.getActionId(), // Pass optional actionId from app for consistency
                        request.getPlaidTransactionId() // Pass optional Plaid transaction ID for
                // fallback lookup
                );

        return ResponseEntity.status(HttpStatus.CREATED).body(action);
    }

    @PutMapping("/{transactionId}/actions/{actionId}")
    public ResponseEntity<TransactionActionTable> updateAction(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String transactionId,
            @PathVariable final String actionId,
            @RequestBody final UpdateActionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (actionId == null || actionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Action ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final TransactionActionTable action =
                actionService.updateAction(
                        user,
                        actionId,
                        request.getTitle(),
                        request.getDescription(),
                        request.getDueDate(),
                        request.getReminderDate(),
                        request.getIsCompleted(),
                        request.getPriority(),
                        request.getReminderDismissed());

        return ResponseEntity.ok(action);
    }

    @DeleteMapping("/{transactionId}/actions/{actionId}")
    public ResponseEntity<Void> deleteAction(
            @AuthenticationPrincipal final UserDetails userDetails,
            @PathVariable final String transactionId,
            @PathVariable final String actionId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (actionId == null || actionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Action ID is required");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        actionService.deleteAction(user, actionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all actions for the authenticated user (for sync) Endpoint: GET
     * /api/transactions/actions/user Note: Using /actions/user instead of /user/actions to avoid
     * route conflict with /{transactionId}/actions
     */
    @GetMapping("/actions/user")
    public ResponseEntity<List<TransactionActionTable>> getAllActionsForUser(
            @AuthenticationPrincipal final UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        final UserTable user =
                userService
                        .findByEmail(userDetails.getUsername())
                        .orElseThrow(
                                () -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        final List<TransactionActionTable> actions = actionService.getActionsByUserId(user);
        return ResponseEntity.ok(actions);
    }

    // DTOs
    public static class CreateActionRequest {
        private String
                actionId; // Optional: If provided, use this ID (for app-backend ID consistency)
        private String title;
        private String description;
        private String dueDate;
        private String reminderDate;
        private String priority;
        private String plaidTransactionId; // Optional: Plaid transaction ID for fallback lookup if

        // transactionId not found

        public String getActionId() {
            return actionId;
        }

        public void setActionId(final String actionId) {
            this.actionId = actionId;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(final String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }

        public String getDueDate() {
            return dueDate;
        }

        public void setDueDate(final String dueDate) {
            this.dueDate = dueDate;
        }

        public String getReminderDate() {
            return reminderDate;
        }

        public void setReminderDate(final String reminderDate) {
            this.reminderDate = reminderDate;
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(final String priority) {
            this.priority = priority;
        }

        public String getPlaidTransactionId() {
            return plaidTransactionId;
        }

        public void setPlaidTransactionId(final String plaidTransactionId) {
            this.plaidTransactionId = plaidTransactionId;
        }
    }

    public static class UpdateActionRequest {
        private String title;
        private String description;
        private String dueDate;
        private String reminderDate;
        private Boolean isCompleted;
        private String priority;
        private Boolean reminderDismissed;

        public String getTitle() {
            return title;
        }

        public void setTitle(final String title) {
            this.title = title;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(final String description) {
            this.description = description;
        }

        public String getDueDate() {
            return dueDate;
        }

        public void setDueDate(final String dueDate) {
            this.dueDate = dueDate;
        }

        public String getReminderDate() {
            return reminderDate;
        }

        public void setReminderDate(final String reminderDate) {
            this.reminderDate = reminderDate;
        }

        public Boolean getIsCompleted() {
            return isCompleted;
        }

        public void setIsCompleted(final Boolean isCompleted) {
            this.isCompleted = isCompleted;
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(final String priority) {
            this.priority = priority;
        }

        public Boolean getReminderDismissed() {
            return reminderDismissed;
        }

        public void setReminderDismissed(final Boolean reminderDismissed) {
            this.reminderDismissed = reminderDismissed;
        }
    }
}
