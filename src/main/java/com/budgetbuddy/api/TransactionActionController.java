package com.budgetbuddy.api;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionActionService;
import com.budgetbuddy.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Transaction Actions/Reminders
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionActionController {

    private final TransactionActionService actionService;
    private final UserService userService;

    public TransactionActionController(
            final TransactionActionService actionService,
            final UserService userService) {
        this.actionService = actionService;
        this.userService = userService;
    }

    @GetMapping("/{transactionId}/actions")
    public ResponseEntity<List<TransactionActionTable>> getActions(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String transactionId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<TransactionActionTable> actions = actionService.getActionsByTransactionId(user, transactionId);
        return ResponseEntity.ok(actions);
    }

    @PostMapping("/{transactionId}/actions")
    public ResponseEntity<TransactionActionTable> createAction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String transactionId,
            @RequestBody CreateActionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        if (request == null || request.getTitle() == null || request.getTitle().trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Title is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        TransactionActionTable action = actionService.createAction(
                user,
                transactionId,
                request.getTitle(),
                request.getDescription(),
                request.getDueDate(),
                request.getReminderDate(),
                request.getPriority()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(action);
    }

    @PutMapping("/{transactionId}/actions/{actionId}")
    public ResponseEntity<TransactionActionTable> updateAction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String transactionId,
            @PathVariable String actionId,
            @RequestBody UpdateActionRequest request) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (actionId == null || actionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Action ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        TransactionActionTable action = actionService.updateAction(
                user,
                actionId,
                request.getTitle(),
                request.getDescription(),
                request.getDueDate(),
                request.getReminderDate(),
                request.getIsCompleted(),
                request.getPriority()
        );

        return ResponseEntity.ok(action);
    }

    @DeleteMapping("/{transactionId}/actions/{actionId}")
    public ResponseEntity<Void> deleteAction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable String transactionId,
            @PathVariable String actionId) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }
        if (actionId == null || actionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Action ID is required");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        actionService.deleteAction(user, actionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all actions for the authenticated user (for sync)
     * Endpoint: GET /api/transactions/actions/user
     * Note: Using /actions/user instead of /user/actions to avoid route conflict with /{transactionId}/actions
     */
    @GetMapping("/actions/user")
    public ResponseEntity<List<TransactionActionTable>> getAllActionsForUser(
            @AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null || userDetails.getUsername() == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "User not authenticated");
        }

        UserTable user = userService.findByEmail(userDetails.getUsername())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND, "User not found"));

        List<TransactionActionTable> actions = actionService.getActionsByUserId(user);
        return ResponseEntity.ok(actions);
    }

    // DTOs
    public static class CreateActionRequest {
        private String title;
        private String description;
        private String dueDate;
        private String reminderDate;
        private String priority;

        public String getTitle() { return title; }
        public void setTitle(final String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(final String description) { this.description = description; }
        public String getDueDate() { return dueDate; }
        public void setDueDate(final String dueDate) { this.dueDate = dueDate; }
        public String getReminderDate() { return reminderDate; }
        public void setReminderDate(final String reminderDate) { this.reminderDate = reminderDate; }
        public String getPriority() { return priority; }
        public void setPriority(final String priority) { this.priority = priority; }
    }

    public static class UpdateActionRequest {
        private String title;
        private String description;
        private String dueDate;
        private String reminderDate;
        private Boolean isCompleted;
        private String priority;

        public String getTitle() { return title; }
        public void setTitle(final String title) { this.title = title; }
        public String getDescription() { return description; }
        public void setDescription(final String description) { this.description = description; }
        public String getDueDate() { return dueDate; }
        public void setDueDate(final String dueDate) { this.dueDate = dueDate; }
        public String getReminderDate() { return reminderDate; }
        public void setReminderDate(final String reminderDate) { this.reminderDate = reminderDate; }
        public Boolean getIsCompleted() { return isCompleted; }
        public void setIsCompleted(final Boolean isCompleted) { this.isCompleted = isCompleted; }
        public String getPriority() { return priority; }
        public void setPriority(final String priority) { this.priority = priority; }
    }
}

