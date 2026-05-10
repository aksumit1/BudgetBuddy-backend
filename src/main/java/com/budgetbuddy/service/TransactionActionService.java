package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.util.IdGenerator;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Service for managing transaction actions/reminders */
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
// PMD's OnlyOneReturn fights guard-clause idiom — the codebase intentionally
// uses early returns for clarity (validation guards, fail-fast patterns).
@SuppressWarnings("PMD.OnlyOneReturn")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@Service
public class TransactionActionService {

    private static final String USER_IS_REQUIRED = "User is required";

    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionActionService.class);

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
     *
     * @param actionId Optional action ID from app. If provided and valid, use it for consistency.
     *     If not provided, generate a new UUID.
     */
    public TransactionActionTable createAction(
            final UserTable user,
            final String transactionId,
            final String title,
            final String description,
            final String dueDate,
            final String reminderDate,
            final String priority) {
        return createAction(
                user,
                transactionId,
                title,
                description,
                dueDate,
                reminderDate,
                priority,
                null,
                null);
    }

    /**
     * Create a new transaction action with optional action ID
     *
     * @param plaidTransactionId Optional Plaid transaction ID for fallback lookup if transactionId
     *     not found
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
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }
        if (title == null || title.isBlank()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Title is required");
        }

        // Verify transaction exists and belongs to user
        // Try to find by transactionId first
        Optional<TransactionTable> transactionOpt = transactionRepository.findById(transactionId);

        // If not found and plaidTransactionId is provided, try lookup by Plaid ID
        if (transactionOpt.isEmpty()
                && plaidTransactionId != null
                && !plaidTransactionId.isEmpty()) {
            LOGGER.debug(
                    "Transaction {} not found by ID, trying Plaid ID: {}",
                    transactionId,
                    plaidTransactionId);
            transactionOpt = transactionRepository.findByPlaidTransactionId(plaidTransactionId);
            if (transactionOpt.isPresent()) {
                final TransactionTable foundTransaction = transactionOpt.get();
                final String foundTransactionId = foundTransaction.getTransactionId();

                // CRITICAL: Log if Plaid ID matches but transaction ID doesn't - indicates ID
                // mismatch
                // Use case-insensitive comparison
                if (!IdGenerator.equalsIgnoreCase(foundTransactionId, transactionId)) {
                    LOGGER.warn(
                            "⚠️ ID MISMATCH: Transaction found by Plaid ID {} but transaction IDs don't match. "
                                    + "Requested ID: {}, Found ID: {}. This indicates an ID generation mismatch between app and backend. "
                                    + "Using found transaction ID: {}",
                            plaidTransactionId,
                            transactionId,
                            foundTransactionId,
                            foundTransactionId);
                } else {
                    LOGGER.info(
                            "Found transaction by Plaid ID {} (transaction ID matches: {})",
                            plaidTransactionId,
                            transactionId);
                }
            }
        }

        if (transactionOpt.isEmpty()) {
            LOGGER.warn(
                    "Transaction {} not found by ID or Plaid ID {} for user {} when creating action. Transaction may not be synced yet.",
                    transactionId,
                    plaidTransactionId != null ? plaidTransactionId : "N/A",
                    user.getEmail());
            throw new AppException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found");
        }

        final TransactionTable transaction = transactionOpt.get();
        if (!transaction.getUserId().equals(user.getUserId())) {
            throw new AppException(
                    ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
        }

        // Use the found transaction's ID (which might be different from the requested
        // transactionId)
        // This ensures the action is linked to the correct transaction in the backend
        final String actualTransactionId = transaction.getTransactionId();

        // Log if we're using a different transaction ID than requested
        // Use case-insensitive comparison
        if (!IdGenerator.equalsIgnoreCase(actualTransactionId, transactionId)) {
            LOGGER.info(
                    "Using transaction ID {} (different from requested {}) for action creation. "
                            + "This is expected when Plaid ID lookup finds a transaction with a different generated ID.",
                    actualTransactionId,
                    transactionId);
        }

        final TransactionActionTable action = new TransactionActionTable();

        // Use provided actionId if valid, otherwise generate new UUID
        if (actionId != null && !actionId.isEmpty() && IdGenerator.isValidUUID(actionId)) {
            // CRITICAL FIX: Normalize ID to lowercase before checking for existing
            // This ensures we check with the normalized ID that will be saved
            final String normalizedId = IdGenerator.normalizeUUID(actionId);
            // Check if action with this ID already exists (using normalized ID)
            final Optional<TransactionActionTable> existingById =
                    actionRepository.findById(normalizedId);
            if (existingById.isPresent()) {
                final TransactionActionTable existing = existingById.get();
                // CRITICAL FIX: Verify the existing action belongs to the same user and transaction
                // This ensures idempotent behavior - return existing action instead of throwing
                // error
                if (existing.getUserId().equals(user.getUserId())
                        && existing.getTransactionId().equals(actualTransactionId)) {
                    // Same action (same user, same transaction) - return existing (idempotent)
                    LOGGER.info(
                            "Action with ID {} already exists for transaction {} and user {}. Returning existing for idempotency.",
                            normalizedId,
                            actualTransactionId,
                            user.getUserId());
                    return existing;
                } else {
                    // Action exists but belongs to different user or transaction -
                    // security/conflict issue
                    LOGGER.warn(
                            "Action with ID {} already exists but belongs to different user or transaction. Generating new UUID.",
                            normalizedId);
                    // Fall through to generate new UUID
                }
            }
            // Set normalized ID
            action.setActionId(normalizedId);
            LOGGER.debug("Using provided action ID (normalized): {} -> {}", actionId, normalizedId);
        } else {
            // Generate new UUID
            // CRITICAL FIX: Normalize generated UUID to lowercase for consistency
            final String generatedId = UUID.randomUUID().toString().toLowerCase(Locale.ROOT);
            action.setActionId(generatedId);
            LOGGER.debug("Generated new action ID (normalized): {}", generatedId);
        }
        // Use the actual transaction ID (from the found transaction, which might differ from
        // requested ID)
        action.setTransactionId(actualTransactionId);
        action.setUserId(user.getUserId());
        action.setTitle(title.trim());
        action.setDescription(
                description != null && !description.isBlank() ? description.trim() : null);
        action.setDueDate(dueDate);
        action.setReminderDate(reminderDate);

        // Validate reminder date against due date (logs warning if reminder is after due date)
        if (reminderDate != null && dueDate != null) {
            reminderNotificationService.validateReminderDate(reminderDate, dueDate);
        }

        action.setIsCompleted(false);
        action.setPriority(priority != null ? priority.toUpperCase(Locale.ROOT) : "MEDIUM");
        action.setCreatedAt(Instant.now());
        action.setUpdatedAt(Instant.now());

        actionRepository.save(action);
        LOGGER.info(
                "Created action {} for transaction {} by user {}",
                action.getActionId(),
                transactionId,
                user.getEmail());
        return action;
    }

    /** Update an existing transaction action */
    public TransactionActionTable updateAction(
            final UserTable user,
            final String actionId,
            final String title,
            final String description,
            final String dueDate,
            final String reminderDate,
            final Boolean isCompleted,
            final String priority,
            final Boolean reminderDismissed) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (actionId == null || actionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Action ID is required");
        }

        LOGGER.debug("Looking up action with ID: {} for user: {}", actionId, user.getEmail());
        final TransactionActionTable action =
                actionRepository
                        .findById(actionId)
                        .orElseThrow(
                                () -> {
                                    LOGGER.warn(
                                            "Action not found: {} for user: {}",
                                            actionId,
                                            user.getEmail());
                                    return new AppException(
                                            ErrorCode.RECORD_NOT_FOUND, "Action not found");
                                });

        if (!action.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Action does not belong to user");
        }

        if (title != null && !title.isBlank()) {
            action.setTitle(title.trim());
        }
        if (description != null) {
            action.setDescription(description.isBlank() ? null : description.trim());
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
            action.setPriority(priority.toUpperCase(Locale.ROOT));
        }
        if (reminderDismissed != null) {
            action.setReminderDismissed(reminderDismissed);
        }
        action.setUpdatedAt(Instant.now());

        actionRepository.save(action);
        LOGGER.info("Updated action {} for user {}", actionId, user.getEmail());
        return action;
    }

    /** Delete a transaction action */
    public void deleteAction(final UserTable user, final String actionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (actionId == null || actionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Action ID is required");
        }

        final TransactionActionTable action =
                actionRepository
                        .findById(actionId)
                        .orElseThrow(
                                () ->
                                        new AppException(
                                                ErrorCode.RECORD_NOT_FOUND, "Action not found"));

        if (!action.getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.UNAUTHORIZED_ACCESS, "Action does not belong to user");
        }

        actionRepository.delete(actionId);
        LOGGER.info("Deleted action {} for user {}", actionId, user.getEmail());
    }

    /**
     * Get all actions for a transaction Note: Returns actions even if transaction doesn't exist yet
     * (transaction may not be synced from Plaid) This allows actions to be created/updated before
     * the transaction is synced
     */
    public List<TransactionActionTable> getActionsByTransactionId(
            final UserTable user, final String transactionId) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        if (transactionId == null || transactionId.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Transaction ID is required");
        }

        // Get actions for this transaction
        final List<TransactionActionTable> actions =
                actionRepository.findByTransactionId(transactionId);

        // Filter to only return actions that belong to the user (security check)
        // This ensures users can only see their own actions
        final List<TransactionActionTable> userActions =
                actions.stream()
                        .filter(
                                action ->
                                        action.getUserId() != null
                                                && action.getUserId().equals(user.getUserId()))
                        .collect(Collectors.toList());

        // Optional: Verify transaction exists and belongs to user (for logging/debugging)
        // But don't throw error if transaction doesn't exist - actions can exist before transaction
        // is synced
        final Optional<TransactionTable> transaction =
                transactionRepository.findById(transactionId);
        if (transaction.isPresent()) {
            // Transaction exists - verify it belongs to user
            if (!transaction.get().getUserId().equals(user.getUserId())) {
                throw new AppException(
                        ErrorCode.UNAUTHORIZED_ACCESS, "Transaction does not belong to user");
            }
        } else {
            // Transaction doesn't exist yet - this is OK, actions can exist before transaction is
            // synced
            LOGGER.debug(
                    "Transaction {} not found in backend, but returning {} actions (transaction may not be synced yet)",
                    transactionId,
                    userActions.size());
        }

        return userActions;
    }

    /** Get all actions for a user */
    public List<TransactionActionTable> getActionsByUserId(final UserTable user) {
        if (user == null || user.getUserId() == null || user.getUserId().isEmpty()) {
            throw new AppException(ErrorCode.INVALID_INPUT, USER_IS_REQUIRED);
        }
        return actionRepository.findByUserId(user.getUserId());
    }
}
