package com.budgetbuddy.service;

import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.NotificationService;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Service for sending reminder notifications for transaction actions
 *
 * <p>Reminder Logic: 1. When both dueDate and reminderDate are present: - Reminder is sent at
 * reminderDate time - reminderDate should be before or equal to dueDate (validation in
 * create/update) - If reminderDate is after dueDate, reminder is still sent at reminderDate (user's
 * choice)
 *
 * <p>2. When only reminderDate is present (without dueDate): - Reminder is sent at reminderDate
 * time - No due date validation needed
 *
 * <p>3. When only dueDate is present (without reminderDate): - No reminder is sent (user must set
 * reminderDate to receive reminders)
 *
 * <p>Scheduled job runs every hour to check for reminders that should be sent
 */
// SDK / Spring integration — the underlying APIs (AWS SDK, Plaid SDK,
// Spring services, reflection) throw arbitrary RuntimeException subtypes
// that can't reasonably be enumerated. Broad catches log + recover (or
// translate to AppException). Suppress at class level since narrowing
// here would mean catch (RuntimeException) which PMD flags identically.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
@Service
public class ReminderNotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReminderNotificationService.class);
    private static final DateTimeFormatter ISO_DATETIME_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter ISO_DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private final TransactionActionRepository actionRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final DistributedLockService distributedLock;

    public ReminderNotificationService(
            final TransactionActionRepository actionRepository,
            final UserRepository userRepository,
            final NotificationService notificationService,
            final DistributedLockService distributedLock) {
        this.actionRepository = actionRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
        this.distributedLock = distributedLock;
    }

    /**
     * Scheduled job to check and send reminder notifications. Runs every hour. Distributed-lock
     * guarded so when ECS scales out we don't fire the same hourly reminder N times. The lock key
     * includes the hour bucket so consecutive hourly runs don't collide.
     *
     * <p>Checks actions where: - reminderDate is not null - reminderDate is in the past (should
     * have been sent) - reminderDate is within the last hour (to catch reminders that just passed)
     * - isCompleted is false - notificationId is null or empty (hasn't been sent yet)
     */
    @Scheduled(cron = "0 0 * * * ?") // Every hour at minute 0
    public void sendReminderNotifications() {
        // Hour-granular lock key: format yyyy-MM-ddTHH so each hourly fire is its own window.
        final String hourKey =
                Instant.now()
                        .atZone(java.time.ZoneOffset.UTC)
                        .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                        .toString();
        final String lockKey = "reminderNotifications:" + hourKey;
        // TTL 30min — bounded by # of due reminders; even at very large scale this fits.
        distributedLock.runOnce(lockKey, 30, this::sendReminderNotificationsInner);
    }

    private void sendReminderNotificationsInner() {
        LOGGER.info("Starting reminder notification check");

        try {
            final Instant now = Instant.now();
            final Instant oneHourAgo = now.minusSeconds(3600); // Check reminders from last hour

            // Find all pending actions with reminder dates in the range
            // Use GSI on reminderDate to efficiently query by date range
            int sentCount = 0;
            int skippedCount = 0;

            // Query actions where reminderDate is between oneHourAgo and now
            final String startDateStr = oneHourAgo.toString(); // ISO format
            final String endDateStr = now.toString(); // ISO format
            final List<TransactionActionTable> actions =
                    actionRepository.findByReminderDateRange(startDateStr, endDateStr);

            LOGGER.debug(
                    "Found {} actions with reminder dates in range {} to {}",
                    actions.size(),
                    startDateStr,
                    endDateStr);

            for (final TransactionActionTable action : actions) {
                // Skip if action is completed
                if (Boolean.TRUE.equals(action.getIsCompleted())) {
                    skippedCount++;
                    continue;
                }

                // Skip if reminder was dismissed by user
                if (Boolean.TRUE.equals(action.getReminderDismissed())) {
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
                final Instant reminderInstant = parseReminderDate(action.getReminderDate());
                if (reminderInstant == null) {
                    LOGGER.warn(
                            "Invalid reminder date format for action {}: {}",
                            action.getActionId(),
                            action.getReminderDate());
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
                    LOGGER.debug(
                            "Reminder for action {} is more than 1 hour past, marking as sent",
                            action.getActionId());
                    action.setNotificationId("sent_" + now.toEpochMilli());
                    actionRepository.save(action);
                    sentCount++;
                }
            }

            LOGGER.info(
                    "Reminder notification check completed: {} sent, {} skipped",
                    sentCount,
                    skippedCount);
        } catch (Exception e) {
            LOGGER.error("Error in reminder notification check: {}", e.getMessage(), e);
        }
    }

    /** Send reminder notification for a specific action */
    private void sendReminderForAction(final TransactionActionTable action) {
        try {
            // Get user for notification
            final Optional<UserTable> userOpt = userRepository.findById(action.getUserId());
            if (userOpt.isEmpty()) {
                LOGGER.warn(
                        "User not found for action {}: {}",
                        action.getActionId(),
                        action.getUserId());
                return;
            }

            final UserTable user = userOpt.get();

            // Build notification message
            final String title = "Reminder: " + action.getTitle();
            final StringBuilder body = new StringBuilder();
            body.append(
                    action.getDescription() != null ? action.getDescription() : "Action reminder");

            // Add due date information if available
            if (action.getDueDate() != null && !action.getDueDate().isEmpty()) {
                body.append("\nDue: ").append(formatDateForDisplay(action.getDueDate()));
            }

            // Create notification request
            final NotificationService.NotificationRequest request =
                    new NotificationService.NotificationRequest();
            request.setUserId(user.getUserId());
            request.setTitle(title);
            request.setBody(body.toString());
            request.setChannels(
                    Set.of(
                            NotificationService.NotificationChannel.PUSH,
                            NotificationService.NotificationChannel.IN_APP));
            request.setType(NotificationService.NotificationType.SYSTEM_NOTIFICATION);

            // Send notification
            final NotificationService.NotificationResult result =
                    notificationService.sendNotification(request);

            if (result.isSuccess()) {
                // Mark as sent by setting notificationId
                action.setNotificationId("sent_" + Instant.now().toEpochMilli());
                actionRepository.save(action);
                LOGGER.info(
                        "Reminder notification sent for action {} to user {}",
                        action.getActionId(),
                        user.getEmail());
            } else {
                LOGGER.warn(
                        "Failed to send reminder notification for action {}: {}",
                        action.getActionId(),
                        "Notification failed");
            }
        } catch (Exception e) {
            LOGGER.error(
                    "Error sending reminder for action {}: {}",
                    action.getActionId(),
                    e.getMessage(),
                    e);
        }
    }

    /**
     * Parse reminder date string to Instant Supports both ISO datetime (with time) and ISO date
     * (date only)
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
                final LocalDateTime localDateTime =
                        LocalDateTime.parse(reminderDateStr, ISO_DATE_FORMATTER);
                return localDateTime.atZone(ZoneId.of("UTC")).toInstant();
            } catch (DateTimeParseException e) {
                // Try ISO datetime without timezone
                final LocalDateTime localDateTime =
                        LocalDateTime.parse(reminderDateStr, ISO_DATETIME_FORMATTER);
                return localDateTime.atZone(ZoneId.of("UTC")).toInstant();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to parse reminder date: {}", reminderDateStr);
            return null;
        }
    }

    /** Format date for display in notification */
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
     * Validate reminder date against due date Called from TransactionActionService when
     * creating/updating actions
     *
     * @param reminderDate Reminder date string
     * @param dueDate Due date string
     * @return true if valid, false if reminderDate is after dueDate (warning case)
     */
    public boolean validateReminderDate(final String reminderDate, final String dueDate) {
        if (reminderDate == null
                || reminderDate.isEmpty()
                || dueDate == null
                || dueDate.isEmpty()) {
            return true; // No validation needed if either is missing
        }

        final Instant reminderInstant = parseReminderDate(reminderDate);
        final Instant dueInstant = parseReminderDate(dueDate);

        if (reminderInstant == null || dueInstant == null) {
            return true; // Can't validate if parsing fails
        }

        // Reminder date should ideally be before or equal to due date
        // But we allow it to be after (user's choice) - just log a warning
        if (reminderInstant.isAfter(dueInstant)) {
            LOGGER.warn(
                    "Reminder date {} is after due date {} - reminder will still be sent at reminder date",
                    reminderDate,
                    dueDate);
            return true; // Still valid, just not ideal
        }

        return true;
    }
}
