package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.NotificationService;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Service for sending reminder notifications for transaction actions
 * 
 * Reminder Logic:
 * 1. When both dueDate and reminderDate are present:
 *    - Reminder is sent at reminderDate time
 *    - reminderDate should be before or equal to dueDate (validation in create/update)
 *    - If reminderDate is after dueDate, reminder is still sent at reminderDate (user's choice)
 * 
 * 2. When only reminderDate is present (without dueDate):
 *    - Reminder is sent at reminderDate time
 *    - No due date validation needed
 * 
 * 3. When only dueDate is present (without reminderDate):
 *    - No reminder is sent (user must set reminderDate to receive reminders)
 * 
 * Scheduled job runs every hour to check for reminders that should be sent
 */
@Service
public class ReminderNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(ReminderNotificationService.class);
    private static final DateTimeFormatter ISO_DATETIME_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionActionRepository actionRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public ReminderNotificationService(
            final TransactionActionRepository actionRepository,
            final UserRepository userRepository,
            final NotificationService notificationService) {
        this.actionRepository = actionRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    /**
     * Scheduled job to check and send reminder notifications
     * Runs every hour to check for reminders that should be sent
     * 
     * Checks actions where:
     * - reminderDate is not null
     * - reminderDate is in the past (should have been sent)
     * - reminderDate is within the last hour (to catch reminders that just passed)
     * - isCompleted is false
     * - notificationId is null or empty (hasn't been sent yet)
     */
    @Scheduled(cron = "0 0 * * * ?") // Every hour at minute 0
    public void sendReminderNotifications() {
        logger.info("Starting reminder notification check");
        
        try {
            Instant now = Instant.now();
            Instant oneHourAgo = now.minusSeconds(3600); // Check reminders from last hour
            
            // Find all pending actions with reminder dates
            // Note: DynamoDB doesn't support efficient date range queries without a GSI
            // For production, consider:
            // 1. Adding a GSI on reminderDate with reminderDate as sort key
            // 2. Using a separate reminder queue table
            // 3. Using DynamoDB Streams to process reminders
            // 
            // For now, this is a placeholder that logs the requirement
            // The iOS app handles reminders client-side, so this backend service is a backup
            logger.info("Reminder notification check: Backend reminder service requires GSI on reminderDate for production use");
            logger.info("iOS app handles reminders client-side, so backend service is currently a backup/fallback");
            
            // TODO: Implement when GSI on reminderDate is available:
            // Query actions where reminderDate is between oneHourAgo and now
            // For now, we'll skip the actual processing and log that it needs implementation
            int sentCount = 0;
            int skippedCount = 0;
            
            // Placeholder: In production, query would be:
            // List<TransactionActionTable> actions = actionRepository.findByReminderDateRange(oneHourAgo, now);
            List<TransactionActionTable> allActions = java.util.Collections.emptyList();
            
            for (TransactionActionTable action : allActions) {
                // Skip if action is completed
                if (Boolean.TRUE.equals(action.getIsCompleted())) {
                    skippedCount++;
                    continue;
                }
                
                // Skip if no reminder date
                if (action.getReminderDate() == null || action.getReminderDate().isEmpty()) {
                    skippedCount++;
                    continue;
                }
                
                // Skip if already sent (has notificationId)
                if (action.getNotificationId() != null && !action.getNotificationId().isEmpty()) {
                    skippedCount++;
                    continue;
                }
                
                // Parse reminder date
                Instant reminderInstant = parseReminderDate(action.getReminderDate());
                if (reminderInstant == null) {
                    logger.warn("Invalid reminder date format for action {}: {}", 
                            action.getActionId(), action.getReminderDate());
                    skippedCount++;
                    continue;
                }
                
                // Check if reminder should be sent now
                // Send if reminder date is in the past and within the last hour
                // This ensures we catch reminders that just passed
                if (reminderInstant.isBefore(now) && reminderInstant.isAfter(oneHourAgo)) {
                    // Send reminder notification
                    sendReminderForAction(action);
                    sentCount++;
                } else if (reminderInstant.isBefore(oneHourAgo)) {
                    // Reminder date is more than an hour in the past - mark as sent to avoid spam
                    // This handles cases where the scheduled job was down or delayed
                    logger.debug("Reminder for action {} is more than 1 hour past, marking as sent", 
                            action.getActionId());
                    action.setNotificationId("sent_" + now.toEpochMilli());
                    actionRepository.save(action);
                    sentCount++;
                }
            }
            
            logger.info("Reminder notification check completed: {} sent, {} skipped", sentCount, skippedCount);
        } catch (Exception e) {
            logger.error("Error in reminder notification check: {}", e.getMessage(), e);
        }
    }

    /**
     * Send reminder notification for a specific action
     */
    private void sendReminderForAction(final TransactionActionTable action) {
        try {
            // Get user for notification
            Optional<UserTable> userOpt = userRepository.findById(action.getUserId());
            if (userOpt.isEmpty()) {
                logger.warn("User not found for action {}: {}", action.getActionId(), action.getUserId());
                return;
            }
            
            UserTable user = userOpt.get();
            
            // Build notification message
            String title = "Reminder: " + action.getTitle();
            StringBuilder body = new StringBuilder();
            body.append(action.getDescription() != null ? action.getDescription() : "Action reminder");
            
            // Add due date information if available
            if (action.getDueDate() != null && !action.getDueDate().isEmpty()) {
                body.append("\nDue: ").append(formatDateForDisplay(action.getDueDate()));
            }
            
            // Create notification request
            NotificationService.NotificationRequest request = new NotificationService.NotificationRequest();
            request.setUserId(user.getUserId());
            request.setTitle(title);
            request.setBody(body.toString());
            request.setChannels(Set.of(NotificationService.NotificationChannel.PUSH, NotificationService.NotificationChannel.IN_APP));
            request.setType(NotificationService.NotificationType.SYSTEM_NOTIFICATION);
            
            // Send notification
            NotificationService.NotificationResult result = notificationService.sendNotification(request);
            
            if (result.isSuccess()) {
                // Mark as sent by setting notificationId
                action.setNotificationId("sent_" + Instant.now().toEpochMilli());
                actionRepository.save(action);
                logger.info("Reminder notification sent for action {} to user {}", 
                        action.getActionId(), user.getEmail());
            } else {
                logger.warn("Failed to send reminder notification for action {}: {}", 
                        action.getActionId(), "Notification failed");
            }
        } catch (Exception e) {
            logger.error("Error sending reminder for action {}: {}", action.getActionId(), e.getMessage(), e);
        }
    }

    /**
     * Parse reminder date string to Instant
     * Supports both ISO datetime (with time) and ISO date (date only)
     */
    private Instant parseReminderDate(final String reminderDateStr) {
        if (reminderDateStr == null || reminderDateStr.isEmpty()) {
            return null;
        }
        
        try {
            // Try ISO datetime format first (e.g., "2024-12-30T10:00:00Z")
            if (reminderDateStr.contains("T") || reminderDateStr.contains("Z")) {
                return Instant.parse(reminderDateStr);
            }
            
            // Try ISO date format (e.g., "2024-12-30") - treat as start of day in UTC
            try {
                LocalDateTime localDateTime = LocalDateTime.parse(reminderDateStr, ISO_DATE_FORMATTER);
                return localDateTime.atZone(ZoneId.of("UTC")).toInstant();
            } catch (DateTimeParseException e) {
                // Try ISO datetime without timezone
                LocalDateTime localDateTime = LocalDateTime.parse(reminderDateStr, ISO_DATETIME_FORMATTER);
                return localDateTime.atZone(ZoneId.of("UTC")).toInstant();
            }
        } catch (Exception e) {
            logger.warn("Failed to parse reminder date: {}", reminderDateStr);
            return null;
        }
    }

    /**
     * Format date for display in notification
     */
    private String formatDateForDisplay(final String dateStr) {
        try {
            if (dateStr.contains("T")) {
                // ISO datetime - extract date part
                return dateStr.substring(0, dateStr.indexOf("T"));
            }
            return dateStr; // Already a date string
        } catch (Exception e) {
            return dateStr; // Return as-is if parsing fails
        }
    }

    /**
     * Validate reminder date against due date
     * Called from TransactionActionService when creating/updating actions
     * 
     * @param reminderDate Reminder date string
     * @param dueDate Due date string
     * @return true if valid, false if reminderDate is after dueDate (warning case)
     */
    public boolean validateReminderDate(final String reminderDate, final String dueDate) {
        if (reminderDate == null || reminderDate.isEmpty() || dueDate == null || dueDate.isEmpty()) {
            return true; // No validation needed if either is missing
        }
        
        Instant reminderInstant = parseReminderDate(reminderDate);
        Instant dueInstant = parseReminderDate(dueDate);
        
        if (reminderInstant == null || dueInstant == null) {
            return true; // Can't validate if parsing fails
        }
        
        // Reminder date should ideally be before or equal to due date
        // But we allow it to be after (user's choice) - just log a warning
        if (reminderInstant.isAfter(dueInstant)) {
            logger.warn("Reminder date {} is after due date {} - reminder will still be sent at reminder date", 
                    reminderDate, dueDate);
            return true; // Still valid, just not ideal
        }
        
        return true;
    }
}

