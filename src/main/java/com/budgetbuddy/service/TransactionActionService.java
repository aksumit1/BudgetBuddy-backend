package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing transaction actions/reminders
 */
@Service
public class TransactionActionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionActionService.class);

    private final TransactionActionRepository actionRepository;
    private final TransactionRepository transactionRepository;

    public TransactionActionService(
            final TransactionActionRepository actionRepository,
            final TransactionRepository transactionRepository) {
        this.actionRepository = actionRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Create a new transaction action
     */
    public TransactionActionTable createAction(
            final UserTable user,
            final String transactionId,
            final String title,
            final String description,
            final String dueDate,
            final String reminderDate,
            final String priority) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        if (title == null || title.trim().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Title is required");
        }

        // Verify transaction exists and belongs to user
        Optional<TransactionTable> transaction = transactionRepository.findById(transactionId);
        if (transaction.isEmpty()) {
            throw new AppException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found");
        }
        if (!transaction.get().getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }

        TransactionActionTable action = new TransactionActionTable();
        action.setActionId(UUID.randomUUID().toString());
        action.setTransactionId(transactionId);
        action.setUserId(user.getUserId());
        action.setTitle(title.trim());
        action.setDescription(description != null && !description.trim().isEmpty() ? description.trim() : null);
        action.setDueDate(dueDate);
        action.setReminderDate(reminderDate);
        action.setIsCompleted(false);
        action.setPriority(priority != null ? priority.toUpperCase() : "MEDIUM");
        action.setCreatedAt(Instant.now());
        action.setUpdatedAt(Instant.now());

        actionRepository.save(action);
        logger.info("Created action {} for transaction {} by user {}", action.getActionId(), transactionId, user.getEmail());
        return action;
    }

    /**
     * Update an existing transaction action
     */
    public TransactionActionTable updateAction(
            final UserTable user,
            final String actionId,
            final String title,
            final String description,
            final String dueDate,
            final String reminderDate,
            final Boolean isCompleted,
            final String priority) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (actionId == null || actionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Action ID is required");
        }

        TransactionActionTable action = actionRepository.findById(actionId)
                .orElseThrow(() -> new AppException(ErrorCode.RECORD_NOT_FOUND, "Action not found"));

        if (!action.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Action does not belong to user");
        }

        if (title != null && !title.trim().isEmpty()) {
            action.setTitle(title.trim());
        }
        if (description != null) {
            action.setDescription(description.trim().isEmpty() ? null : description.trim());
        }
        if (dueDate != null) {
            action.setDueDate(dueDate);
        }
        if (reminderDate != null) {
            action.setReminderDate(reminderDate);
        }
        if (isCompleted != null) {
            action.setIsCompleted(isCompleted);
        }
        if (priority != null) {
            action.setPriority(priority.toUpperCase());
        }
        action.setUpdatedAt(Instant.now());

        actionRepository.save(action);
        logger.info("Updated action {} for user {}", actionId, user.getEmail());
        return action;
    }

    /**
     * Delete a transaction action
     */
    public void deleteAction(final UserTable user, final String actionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (actionId == null || actionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Action ID is required");
        }

        TransactionActionTable action = actionRepository.findById(actionId)
                .orElseThrow(() -> new AppException(ErrorCode.RECORD_NOT_FOUND, "Action not found"));

        if (!action.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Action does not belong to user");
        }

        actionRepository.delete(actionId);
        logger.info("Deleted action {} for user {}", actionId, user.getEmail());
    }

    /**
     * Get all actions for a transaction
     */
    public List<TransactionActionTable> getActionsByTransactionId(final UserTable user, final String transactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        // Verify transaction belongs to user
        Optional<TransactionTable> transaction = transactionRepository.findById(transactionId);
        if (transaction.isEmpty()) {
            throw new AppException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found");
        }
        if (!transaction.get().getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }

        return actionRepository.findByTransactionId(transactionId);
    }

    /**
     * Get all actions for a user
     */
    public List<TransactionActionTable> getActionsByUserId(final UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        return actionRepository.findByUserId(user.getUserId());
    }
}

