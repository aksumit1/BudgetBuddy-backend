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
import java.util.stream.Collectors;
import com.budgetbuddy.util.IdGenerator;

/**
 * Service for managing transaction actions/reminders
 */
@Service
public class TransactionActionService {

    private static final Logger logger = LoggerFactory.getLogger(TransactionActionService.class);

    private final TransactionActionRepository actionRepository;
    private final TransactionRepository transactionRepository;
    private final ReminderNotificationService reminderNotificationService;

    public TransactionActionService(
            final TransactionActionRepository actionRepository,
            final TransactionRepository transactionRepository,
            final ReminderNotificationService reminderNotificationService) {
        this.actionRepository = actionRepository;
        this.transactionRepository = transactionRepository;
        this.reminderNotificationService = reminderNotificationService;
    }

    /**
     * Create a new transaction action
     * @param actionId Optional action ID from app. If provided and valid, use it for consistency.
     *                 If not provided, generate a new UUID.
     */
    public TransactionActionTable createAction(
            final UserTable user,
            final String transactionId,
            final String title,
            final String description,
            final String dueDate,
            final String reminderDate,
            final String priority) {
        return createAction(user, transactionId, title, description, dueDate, reminderDate, priority, null, null);
    }
    
    /**
     * Create a new transaction action with optional action ID
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup if transactionId not found
     */
    public TransactionActionTable createAction(
            final UserTable user,
            final String transactionId,
            final String title,
            final String description,
            final String dueDate,
            final String reminderDate,
            final String priority,
            final String actionId,
            final String plaidTransactionId) {
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
        // Try to find by transactionId first
        Optional<TransactionTable> transactionOpt = transactionRepository.findById(transactionId);
        
        // If not found and plaidTransactionId is provided, try lookup by Plaid ID
        if (transactionOpt.isEmpty() && plaidTransactionId != null && !plaidTransactionId.isEmpty()) {
            logger.debug("Transaction {} not found by ID, trying Plaid ID: {}", transactionId, plaidTransactionId);
            transactionOpt = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
            if (transactionOpt.isPresent()) {
                TransactionTable foundTransaction = transactionOpt.get();
                String foundTransactionId = foundTransaction.getTransactionId();
                
                // CRITICAL: Log if Plaid ID matches but transaction ID doesn't - indicates ID mismatch
                // Use case-insensitive comparison
                if (!com.budgetbuddy.util.IdGenerator.equalsIgnoreCase(foundTransactionId, transactionId)) {
                    logger.warn("⚠️ ID MISMATCH: Transaction found by Plaid ID {} but transaction IDs don't match. " +
                            "Requested ID: {}, Found ID: {}. This indicates an ID generation mismatch between app and backend. " +
                            "Using found transaction ID: {}", 
                            plaidTransactionId, transactionId, foundTransactionId, foundTransactionId);
                } else {
                    logger.info("Found transaction by Plaid ID {} (transaction ID matches: {})", plaidTransactionId, transactionId);
                }
            }
        }
        
        if (transactionOpt.isEmpty()) {
            logger.warn("Transaction {} not found by ID or Plaid ID {} for user {} when creating action. Transaction may not be synced yet.", 
                    transactionId, plaidTransactionId != null ? plaidTransactionId : "N/A", user.getEmail());
            throw new AppException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found");
        }
        
        TransactionTable transaction = transactionOpt.get();
        if (!transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }
        
        // Use the found transaction's ID (which might be different from the requested transactionId)
        // This ensures the action is linked to the correct transaction in the backend
        final String actualTransactionId = transaction.getTransactionId();
        
        // Log if we're using a different transaction ID than requested
        // Use case-insensitive comparison
        if (!com.budgetbuddy.util.IdGenerator.equalsIgnoreCase(actualTransactionId, transactionId)) {
            logger.info("Using transaction ID {} (different from requested {}) for action creation. " +
                    "This is expected when Plaid ID lookup finds a transaction with a different generated ID.", 
                    actualTransactionId, transactionId);
        }

        TransactionActionTable action = new TransactionActionTable();
        
        // Use provided actionId if valid, otherwise generate new UUID
        if (actionId != null && !actionId.isEmpty() && IdGenerator.isValidUUID(actionId)) {
            // Check if action with this ID already exists
            Optional<TransactionActionTable> existingById = actionRepository.findById(actionId);
            if (existingById.isPresent()) {
                throw new AppException(ErrorCode.RECORD_ALREADY_EXISTS, "Action with ID " + actionId + " already exists");
            }
            action.setActionId(actionId);
            logger.debug("Using provided action ID: {}", actionId);
        } else {
            // Generate new UUID
            action.setActionId(UUID.randomUUID().toString());
            logger.debug("Generated new action ID: {}", action.getActionId());
        }
        // Use the actual transaction ID (from the found transaction, which might differ from requested ID)
        action.setTransactionId(actualTransactionId);
        action.setUserId(user.getUserId());
        action.setTitle(title.trim());
        action.setDescription(description != null && !description.trim().isEmpty() ? description.trim() : null);
        action.setDueDate(dueDate);
        action.setReminderDate(reminderDate);
        
        // Validate reminder date against due date (logs warning if reminder is after due date)
        if (reminderDate != null && dueDate != null) {
            reminderNotificationService.validateReminderDate(reminderDate, dueDate);
        }
        
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

        logger.debug("Looking up action with ID: {} for user: {}", actionId, user.getEmail());
        TransactionActionTable action = actionRepository.findById(actionId)
                .orElseThrow(() -> {
                    logger.warn("Action not found: {} for user: {}", actionId, user.getEmail());
                    return new AppException(ErrorCode.RECORD_NOT_FOUND, "Action not found");
                });

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
            // Validate reminder date against due date (logs warning if reminder is after due date)
            if (action.getDueDate() != null) {
                reminderNotificationService.validateReminderDate(reminderDate, action.getDueDate());
            }
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
     * Note: Returns actions even if transaction doesn't exist yet (transaction may not be synced from Plaid)
     * This allows actions to be created/updated before the transaction is synced
     */
    public List<TransactionActionTable> getActionsByTransactionId(final UserTable user, final String transactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "User is required");
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        // Get actions for this transaction
        List<TransactionActionTable> actions = actionRepository.findByTransactionId(transactionId);
        
        // Filter to only return actions that belong to the user (security check)
        // This ensures users can only see their own actions
        List<TransactionActionTable> userActions = actions.stream()
                .filter(action -> action.getUserId() != null && action.getUserId().equals(user.getUserId()))
                .collect(Collectors.toList());
        
        // Optional: Verify transaction exists and belongs to user (for logging/debugging)
        // But don't throw error if transaction doesn't exist - actions can exist before transaction is synced
        Optional<TransactionTable> transaction = transactionRepository.findById(transactionId);
        if (transaction.isPresent()) {
            // Transaction exists - verify it belongs to user
            if (!transaction.get().getUserId().equals(user.getUserId())) {
                throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
            }
        } else {
            // Transaction doesn't exist yet - this is OK, actions can exist before transaction is synced
            logger.debug("Transaction {} not found in backend, but returning {} actions (transaction may not be synced yet)", 
                    transactionId, userActions.size());
        }

        return userActions;
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

