package com.budgetbuddy.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for sending data change notifications to all user's devices
 * Handles real-time sync via push notifications
 */
@Service
public class DataChangeNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(DataChangeNotificationService.class);

    private final PushNotificationService pushNotificationService;
    private final boolean notificationsEnabled;

    public DataChangeNotificationService(
            final PushNotificationService pushNotificationService,
            @org.springframework.beans.factory.annotation.Value("${app.notifications.enabled:true}") final boolean notificationsEnabled) {
        this.pushNotificationService = pushNotificationService;
        this.notificationsEnabled = notificationsEnabled;
    }

    /**
     * Send data change notification to all user's devices
     * This triggers cache invalidation and sync on iOS devices
     * 
     * @param userId User ID
     * @param changeType Type of change (TRANSACTION_CREATED, TRANSACTION_UPDATED, etc.)
     * @param data Additional data about the change
     */
    public void notifyDataChanged(final String userId, final NotificationService.NotificationType changeType, 
                                 final Map<String, Object> data) {
        if (!notificationsEnabled) {
            logger.debug("Notifications disabled, skipping data change notification");
            return;
        }

        if (userId == null || userId.isEmpty()) {
            logger.warn("Cannot send data change notification: userId is null or empty");
            return;
        }

        // Send asynchronously to avoid blocking the main request
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> notificationData = new HashMap<>();
                notificationData.put("type", "data_changed");
                notificationData.put("changeType", changeType.name());
                notificationData.put("silent", true); // Silent notification for background sync
                if (data != null) {
                    notificationData.putAll(data);
                }

                int sentCount = pushNotificationService.sendPushNotificationToAllDevices(
                        userId,
                        "Data Updated", // Title (not shown in silent notifications)
                        "", // Empty body for silent notifications
                        notificationData
                );

                if (sentCount > 0) {
                    logger.info("Data change notification sent to {} device(s) for user: {}, changeType: {}", 
                            sentCount, userId, changeType);
                } else {
                    logger.debug("No devices to notify for user: {}, changeType: {}", userId, changeType);
                }
            } catch (Exception e) {
                logger.error("Failed to send data change notification for user {}: {}", 
                        userId, e.getMessage(), e);
            }
        });
    }

    /**
     * Notify transaction created
     */
    public void notifyTransactionCreated(final String userId, final String transactionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("transactionId", transactionId);
        data.put("entityType", "transaction");
        notifyDataChanged(userId, NotificationService.NotificationType.TRANSACTION_CREATED, data);
    }

    /**
     * Notify transaction updated
     */
    public void notifyTransactionUpdated(final String userId, final String transactionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("transactionId", transactionId);
        data.put("entityType", "transaction");
        notifyDataChanged(userId, NotificationService.NotificationType.TRANSACTION_UPDATED, data);
    }

    /**
     * Notify transaction deleted
     */
    public void notifyTransactionDeleted(final String userId, final String transactionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("transactionId", transactionId);
        data.put("entityType", "transaction");
        notifyDataChanged(userId, NotificationService.NotificationType.TRANSACTION_UPDATED, data);
    }

    /**
     * Notify account created/updated
     */
    public void notifyAccountChanged(final String userId, final String accountId) {
        Map<String, Object> data = new HashMap<>();
        data.put("accountId", accountId);
        data.put("entityType", "account");
        notifyDataChanged(userId, NotificationService.NotificationType.ACCOUNT_LINKED, data);
    }

    /**
     * Notify budget changed
     */
    public void notifyBudgetChanged(final String userId, final String budgetId) {
        Map<String, Object> data = new HashMap<>();
        data.put("budgetId", budgetId);
        data.put("entityType", "budget");
        notifyDataChanged(userId, NotificationService.NotificationType.BUDGET_WARNING, data);
    }

    /**
     * Notify goal changed
     */
    public void notifyGoalChanged(final String userId, final String goalId) {
        Map<String, Object> data = new HashMap<>();
        data.put("goalId", goalId);
        data.put("entityType", "goal");
        notifyDataChanged(userId, NotificationService.NotificationType.GOAL_PROGRESS, data);
    }

    /**
     * Notify batch transactions imported
     */
    public void notifyBatchTransactionsImported(final String userId, final int count) {
        Map<String, Object> data = new HashMap<>();
        data.put("count", count);
        data.put("entityType", "transaction");
        data.put("batch", true);
        notifyDataChanged(userId, NotificationService.NotificationType.TRANSACTION_CREATED, data);
    }
}
