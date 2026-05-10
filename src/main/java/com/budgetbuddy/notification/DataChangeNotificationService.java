package com.budgetbuddy.notification;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for sending data change notifications to all user's devices Handles real-time sync via
 * push notifications
 */
// NotificationService.NotificationType.* enum field access is flagged by
// PMD's LawOfDemeter — false positive on standard enum dispatch.
@SuppressWarnings("PMD.LawOfDemeter")
@Service
public class DataChangeNotificationService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DataChangeNotificationService.class);

    private final PushNotificationService pushNotificationService;
    private final boolean notificationsEnabled;

    public DataChangeNotificationService(
            final PushNotificationService pushNotificationService,
            @org.springframework.beans.factory.annotation.Value("${app.notifications.enabled:true}")
                    final boolean notificationsEnabled) {
        this.pushNotificationService = pushNotificationService;
        this.notificationsEnabled = notificationsEnabled;
    }

    /**
     * Send data change notification to all user's devices This triggers cache invalidation and sync
     * on iOS devices
     *
     * @param userId User ID
     * @param changeType Type of change (TRANSACTION_CREATED, TRANSACTION_UPDATED, etc.)
     * @param data Additional data about the change
     */
    public void notifyDataChanged(
            final String userId,
            final NotificationService.NotificationType changeType,
            final Map<String, Object> data) {
        if (!notificationsEnabled) {
            LOGGER.debug("Notifications disabled, skipping data change notification");
            return;
        }

        if (userId == null || userId.isEmpty()) {
            LOGGER.warn("Cannot send data change notification: userId is null or empty");
            return;
        }

        // Send asynchronously to avoid blocking the main request
        CompletableFuture.runAsync(
                () -> {
                    try {
                        final Map<String, Object> notificationData = new HashMap<>();
                        notificationData.put("type", "data_changed");
                        notificationData.put("changeType", changeType.name());
                        notificationData.put(
                                "silent", true); // Silent notification for background sync
                        if (data != null) {
                            notificationData.putAll(data);
                        }

                        final int sentCount =
                                pushNotificationService.sendPushNotificationToAllDevices(
                                        userId,
                                        "Data Updated", // Title (not shown in silent notifications)
                                        "", // Empty body for silent notifications
                                        notificationData);

                        if (sentCount > 0) {
                            LOGGER.info(
                                    "Data change notification sent to {} device(s) for user: {}, changeType: {}",
                                    sentCount,
                                    userId,
                                    changeType);
                        } else {
                            LOGGER.debug(
                                    "No devices to notify for user: {}, changeType: {}",
                                    userId,
                                    changeType);
                        }
                    } catch (Exception e) {
                        LOGGER.error(
                                "Failed to send data change notification for user {}: {}",
                                userId,
                                e.getMessage(),
                                e);
                    }
                });
    }

    /** Notify transaction created */
    public void notifyTransactionCreated(final String userId, final String transactionId) {
        final Map<String, Object> data = new HashMap<>();
        data.put("transactionId", transactionId);
        data.put("entityType", "transaction");
        notifyDataChanged(userId, NotificationService.NotificationType.TRANSACTION_CREATED, data);
    }

    /** Notify transaction updated */
    public void notifyTransactionUpdated(final String userId, final String transactionId) {
        final Map<String, Object> data = new HashMap<>();
        data.put("transactionId", transactionId);
        data.put("entityType", "transaction");
        notifyDataChanged(userId, NotificationService.NotificationType.TRANSACTION_UPDATED, data);
    }

    /** Notify transaction deleted */
    public void notifyTransactionDeleted(final String userId, final String transactionId) {
        final Map<String, Object> data = new HashMap<>();
        data.put("transactionId", transactionId);
        data.put("entityType", "transaction");
        notifyDataChanged(userId, NotificationService.NotificationType.TRANSACTION_UPDATED, data);
    }

    /** Notify account created/updated */
    public void notifyAccountChanged(final String userId, final String accountId) {
        final Map<String, Object> data = new HashMap<>();
        data.put("accountId", accountId);
        data.put("entityType", "account");
        notifyDataChanged(userId, NotificationService.NotificationType.ACCOUNT_LINKED, data);
    }

    /** Notify budget changed */
    public void notifyBudgetChanged(final String userId, final String budgetId) {
        final Map<String, Object> data = new HashMap<>();
        data.put("budgetId", budgetId);
        data.put("entityType", "budget");
        notifyDataChanged(userId, NotificationService.NotificationType.BUDGET_WARNING, data);
    }

    /** Notify goal changed */
    public void notifyGoalChanged(final String userId, final String goalId) {
        final Map<String, Object> data = new HashMap<>();
        data.put("goalId", goalId);
        data.put("entityType", "goal");
        notifyDataChanged(userId, NotificationService.NotificationType.GOAL_PROGRESS, data);
    }

    /** Notify batch transactions imported */
    public void notifyBatchTransactionsImported(final String userId, final int count) {
        final Map<String, Object> data = new HashMap<>();
        data.put("count", count);
        data.put("entityType", "transaction");
        data.put("batch", true);
        notifyDataChanged(userId, NotificationService.NotificationType.TRANSACTION_CREATED, data);
    }

    /**
     * Flow 6 / O3 — push a "milestone reached" alert when a goal's progress crosses 25/50/75/100
     * for the first time. Mirrors the budget-threshold flow but speaks achievement, not warning.
     */
    public void notifyGoalMilestoneReached(
            final String userId,
            final String goalId,
            final String goalName,
            final int milestone,
            final boolean completed) {
        final Map<String, Object> data = new HashMap<>();
        data.put("goalId", goalId);
        data.put("goalName", goalName);
        data.put("milestone", milestone);
        data.put("completed", completed);
        data.put("entityType", "goal-milestone");
        notifyDataChanged(
                userId,
                completed
                        ? NotificationService.NotificationType.GOAL_ACHIEVED
                        : NotificationService.NotificationType.GOAL_PROGRESS,
                data);
    }

    /**
     * Flow 5 / O8 — push a "you just crossed a budget threshold" alert at the moment a transaction
     * ingestion tips a category past 50/75/90/100%. Fires from the server (truth for transaction
     * ingest) rather than from an iOS polling loop that a suspended app would miss.
     *
     * @param crossedThreshold 50, 75, 90, or 100
     * @param category user-facing category name (e.g. "Groceries")
     * @param percentSpent current spend as a percent of the effective limit
     */
    public void notifyBudgetThresholdCrossed(
            final String userId,
            final String budgetId,
            final String category,
            final int crossedThreshold,
            final double percentSpent) {
        final Map<String, Object> data = new HashMap<>();
        data.put("budgetId", budgetId);
        data.put("category", category);
        data.put("threshold", crossedThreshold);
        data.put("percentSpent", percentSpent);
        data.put("entityType", "budget-threshold");
        notifyDataChanged(userId, NotificationService.NotificationType.BUDGET_WARNING, data);
    }
}
