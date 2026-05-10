package com.budgetbuddy.notification;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

/**
 * Advanced Notification Service Supports multiple notification channels: Email, SMS, Push, In-App
 * Uses AWS SNS for delivery
 *
 * <p>Thread-safe implementation with proper dependency injection
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// SpotBugs flags constructor-injected Spring beans as EI_EXPOSE_REP2,
// but Spring's IoC container intentionally shares the same bean across
// callers — defensive-copying it would break dependency injection.
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring constructor injection — beans are shared by design")
@SuppressWarnings({"PMD.LawOfDemeter", "PMD.AvoidCatchingGenericException"})
@Service
public class NotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationService.class);

    private final SnsClient snsClient;
    private final EmailNotificationService emailService;
    private final PushNotificationService pushService;
    private final String snsTopicArn;
    private final boolean notificationsEnabled;

    public NotificationService(
            final SnsClient snsClient,
            final EmailNotificationService emailService,
            final PushNotificationService pushService,
            @Value("${app.notifications.sns.topic-arn:}") final String snsTopicArn,
            @Value("${app.notifications.enabled:true}") final boolean notificationsEnabled) {
        if (snsClient == null) {
            throw new IllegalArgumentException("SnsClient cannot be null");
        }
        if (emailService == null) {
            throw new IllegalArgumentException("EmailNotificationService cannot be null");
        }
        if (pushService == null) {
            throw new IllegalArgumentException("PushNotificationService cannot be null");
        }
        this.snsClient = snsClient;
        this.emailService = emailService;
        this.pushService = pushService;
        this.snsTopicArn = snsTopicArn;
        this.notificationsEnabled = notificationsEnabled;
    }

    /** Send notification via multiple channels */
    public NotificationResult sendNotification(final NotificationRequest request) {
        if (request == null) {
            throw new AppException(ErrorCode.INVALID_INPUT, "Notification request cannot be null");
        }

        if (!notificationsEnabled) {
            LOGGER.debug("Notifications disabled, skipping");
            return new NotificationResult(false, "Notifications disabled");
        }

        final NotificationResult result = new NotificationResult();
        final Map<String, Boolean> channelResults = new HashMap<>();

        final Set<NotificationChannel> channels = request.getChannels();
        if (channels == null || channels.isEmpty()) {
            LOGGER.warn("No notification channels specified");
            return new NotificationResult(false, "No channels specified");
        }

        // Send via requested channels
        if (channels.contains(NotificationChannel.EMAIL)) {
            final boolean emailSent = sendEmail(request);
            channelResults.put("EMAIL", emailSent);
            result.setEmailSent(emailSent);
        }

        if (channels.contains(NotificationChannel.SMS)) {
            final boolean smsSent = sendSMS(request);
            channelResults.put("SMS", smsSent);
            result.setSmsSent(smsSent);
        }

        if (channels.contains(NotificationChannel.PUSH)) {
            final boolean pushSent = sendPush(request);
            channelResults.put("PUSH", pushSent);
            result.setPushSent(pushSent);
        }

        if (channels.contains(NotificationChannel.IN_APP)) {
            final boolean inAppSent = sendInApp(request);
            channelResults.put("IN_APP", inAppSent);
            result.setInAppSent(inAppSent);
        }

        // Determine overall success
        final boolean overallSuccess = channelResults.values().stream().anyMatch(Boolean::booleanValue);
        result.setSuccess(overallSuccess);
        result.setChannelResults(channelResults);

        LOGGER.info(
                "Notification sent - User: {}, Type: {}, Channels: {}, Success: {}",
                request.getUserId(),
                request.getType(),
                channels,
                overallSuccess);

        return result;
    }

    /** Send email notification */
    private boolean sendEmail(final NotificationRequest request) {
        if (request.getRecipientEmail() == null || request.getRecipientEmail().isEmpty()) {
            LOGGER.warn("Email notification skipped: No email address provided");
            return false;
        }

        try {
            return emailService.sendEmail(
                    request.getUserId(),
                    request.getRecipientEmail(),
                    request.getSubject(),
                    request.getBody(),
                    request.getTemplateId(),
                    request.getTemplateData());
        } catch (Exception e) {
            LOGGER.error("Failed to send email notification: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Send SMS notification via SNS */
    private boolean sendSMS(final NotificationRequest request) {
        if (request.getRecipientPhone() == null || request.getRecipientPhone().isEmpty()) {
            LOGGER.warn("SMS notification skipped: No phone number provided");
            return false;
        }

        try {
            final PublishRequest snsRequest =
                    PublishRequest.builder()
                            .phoneNumber(request.getRecipientPhone())
                            .message(request.getBody() != null ? request.getBody() : "")
                            .messageAttributes(
                                    Map.of(
                                            "notificationType",
                                            MessageAttributeValue.builder()
                                                    .dataType("String")
                                                    .stringValue(
                                                            request.getType() != null
                                                                    ? request.getType().name()
                                                                    : "UNKNOWN")
                                                    .build()))
                            .build();

            final PublishResponse response = snsClient.publish(snsRequest);

            LOGGER.info(
                    "SMS notification sent - MessageId: {}, Phone: {}",
                    response.messageId(),
                    maskPhoneNumber(request.getRecipientPhone()));
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to send SMS notification: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Send push notification */
    private boolean sendPush(final NotificationRequest request) {
        try {
            return pushService.sendPushNotification(
                    request.getUserId(), request.getTitle(), request.getBody(), request.getData());
        } catch (Exception e) {
            LOGGER.error("Failed to send push notification: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Send in-app notification */
    private boolean sendInApp(final NotificationRequest request) {
        try {
            // Store in-app notification in database
            // In production, this would use a notification repository
            LOGGER.debug("In-app notification stored for user: {}", request.getUserId());
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to send in-app notification: {}", e.getMessage(), e);
            return false;
        }
    }

    /** Send notification to SNS topic */
    public boolean publishToTopic(final NotificationRequest request) {
        if (request == null) {
            return false;
        }

        if (snsTopicArn == null || snsTopicArn.isEmpty()) {
            LOGGER.warn("SNS topic ARN not configured");
            return false;
        }

        try {
            final Map<String, Object> message = new HashMap<>();
            message.put("userId", request.getUserId() != null ? request.getUserId() : "unknown");
            message.put("type", request.getType() != null ? request.getType().name() : "UNKNOWN");
            message.put("title", request.getTitle() != null ? request.getTitle() : "");
            message.put("body", request.getBody() != null ? request.getBody() : "");
            message.put(
                    "channels", request.getChannels() != null ? request.getChannels() : Set.of());
            message.put("timestamp", Instant.now().toString());

            final com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            final String messageJson = mapper.writeValueAsString(message);

            final PublishRequest snsRequest =
                    PublishRequest.builder()
                            .topicArn(snsTopicArn)
                            .message(messageJson)
                            .messageAttributes(
                                    Map.of(
                                            "notificationType",
                                            MessageAttributeValue.builder()
                                                    .dataType("String")
                                                    .stringValue(
                                                            request.getType() != null
                                                                    ? request.getType().name()
                                                                    : "UNKNOWN")
                                                    .build()))
                            .build();

            final PublishResponse response = snsClient.publish(snsRequest);

            LOGGER.info(
                    "Notification published to SNS topic - MessageId: {}", response.messageId());
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to publish notification to SNS topic: {}", e.getMessage(), e);
            return false;
        }
    }

    private String maskPhoneNumber(final String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "***-***-" + phone.substring(phone.length() - 4);
    }

    /** Notification Request DTO */
    public static class NotificationRequest {
        private String userId;
        private NotificationType type;
        private String title;
        private String subject;
        private String body;
        private String recipientEmail;
        private String recipientPhone;
        private Set<NotificationChannel> channels;
        private String templateId;
        private Map<String, Object> templateData;
        private Map<String, Object> data;

        // Getters and setters
        public String getUserId() {
            return userId;
        }

        public void setUserId(final String userId) {
            this.userId = userId;
        }

        public NotificationType getType() {
            return type;
        }

        public void setType(final NotificationType type) {
            this.type = type;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(final String title) {
            this.title = title;
        }

        public String getSubject() {
            return subject;
        }

        public void setSubject(final String subject) {
            this.subject = subject;
        }

        public String getBody() {
            return body;
        }

        public void setBody(final String body) {
            this.body = body;
        }

        public String getRecipientEmail() {
            return recipientEmail;
        }

        public void setRecipientEmail(final String recipientEmail) {
            this.recipientEmail = recipientEmail;
        }

        public String getRecipientPhone() {
            return recipientPhone;
        }

        public void setRecipientPhone(final String recipientPhone) {
            this.recipientPhone = recipientPhone;
        }

        public Set<NotificationChannel> getChannels() {
            return channels;
        }

        public void setChannels(final Set<NotificationChannel> channels) {
            this.channels = channels;
        }

        public String getTemplateId() {
            return templateId;
        }

        public void setTemplateId(final String templateId) {
            this.templateId = templateId;
        }

        public Map<String, Object> getTemplateData() {
            return templateData;
        }

        public void setTemplateData(final Map<String, Object> templateData) {
            this.templateData = templateData;
        }

        public Map<String, Object> getData() {
            return data;
        }

        public void setData(final Map<String, Object> data) {
            this.data = data;
        }
    }

    /** Notification Result DTO */
    public static class NotificationResult {
        private boolean success;
        private String message;
        private boolean emailSent;
        private boolean smsSent;
        private boolean pushSent;
        private boolean inAppSent;
        private Map<String, Boolean> channelResults;

        public NotificationResult() {
            this.channelResults = new HashMap<>();
        }

        public NotificationResult(final boolean success, final String message) {
            this.success = success;
            this.message = message;
            this.channelResults = new HashMap<>();
        }

        // Getters and setters
        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(final boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(final String message) {
            this.message = message;
        }

        public boolean isEmailSent() {
            return emailSent;
        }

        public void setEmailSent(final boolean emailSent) {
            this.emailSent = emailSent;
        }

        public boolean isSmsSent() {
            return smsSent;
        }

        public void setSmsSent(final boolean smsSent) {
            this.smsSent = smsSent;
        }

        public boolean isPushSent() {
            return pushSent;
        }

        public void setPushSent(final boolean pushSent) {
            this.pushSent = pushSent;
        }

        public boolean isInAppSent() {
            return inAppSent;
        }

        public void setInAppSent(final boolean inAppSent) {
            this.inAppSent = inAppSent;
        }

        public Map<String, Boolean> getChannelResults() {
            return channelResults;
        }

        public void setChannelResults(final Map<String, Boolean> channelResults) {
            this.channelResults = channelResults;
        }
    }

    /** Notification Type Enum */
    public enum NotificationType {
        TRANSACTION_CREATED,
        TRANSACTION_UPDATED,
        BUDGET_EXCEEDED,
        BUDGET_WARNING,
        GOAL_PROGRESS,
        GOAL_ACHIEVED,
        ACCOUNT_LINKED,
        ACCOUNT_DISCONNECTED,
        PAYMENT_SUCCESS,
        PAYMENT_FAILED,
        SECURITY_ALERT,
        SYSTEM_NOTIFICATION
    }

    /** Notification Channel Enum */
    public enum NotificationChannel {
        EMAIL,
        SMS,
        PUSH,
        IN_APP
    }
}
