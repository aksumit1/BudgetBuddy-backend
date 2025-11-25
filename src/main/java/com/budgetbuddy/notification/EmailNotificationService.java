package com.budgetbuddy.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Email Notification Service using AWS SES
 */
@Service
public class EmailNotificationService {

    private static final Logger logger = LoggerFactory.getLogger(EmailNotificationService.class);

    private final SesClient sesClient;
    private final String fromEmail;

    public EmailNotificationService(
            SesClient sesClient,
            @Value("${app.notifications.email.from:noreply@budgetbuddy.com}") String fromEmail) {
        this.sesClient = sesClient;
        this.fromEmail = fromEmail;
    }

    /**
     * Send email notification
     */
    public boolean sendEmail(String userId, String toEmail, String subject, String body, 
                            String templateId, Map<String, Object> templateData) {
        try {
            // If template is provided, use template
            if (templateId != null && !templateId.isEmpty()) {
                return sendTemplatedEmail(toEmail, templateId, templateData);
            }

            // Otherwise, send plain email
            SendEmailRequest request = SendEmailRequest.builder()
                    .source(fromEmail)
                    .destination(Destination.builder()
                            .toAddresses(toEmail)
                            .build())
                    .message(Message.builder()
                            .subject(Content.builder()
                                    .data(subject)
                                    .charset(StandardCharsets.UTF_8.name())
                                    .build())
                            .body(Body.builder()
                                    .html(Content.builder()
                                            .data(body)
                                            .charset(StandardCharsets.UTF_8.name())
                                            .build())
                                    .build())
                            .build())
                    .build();

            SendEmailResponse response = sesClient.sendEmail(request);
            
            logger.info("Email sent - MessageId: {}, To: {}", response.messageId(), toEmail);
            return true;
        } catch (Exception e) {
            logger.error("Failed to send email: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Send templated email
     */
    private boolean sendTemplatedEmail(String toEmail, String templateId, Map<String, Object> templateData) {
        try {
            // Convert template data to JSON string
            String templateDataJson = com.fasterxml.jackson.databind.ObjectMapper.class.newInstance()
                    .writeValueAsString(templateData);

            SendTemplatedEmailRequest request = SendTemplatedEmailRequest.builder()
                    .source(fromEmail)
                    .destination(Destination.builder()
                            .toAddresses(toEmail)
                            .build())
                    .template(templateId)
                    .templateData(templateDataJson)
                    .build();

            SendTemplatedEmailResponse response = sesClient.sendTemplatedEmail(request);
            
            logger.info("Templated email sent - MessageId: {}, Template: {}, To: {}", 
                    response.messageId(), templateId, toEmail);
            return true;
        } catch (Exception e) {
            logger.error("Failed to send templated email: {}", e.getMessage());
            return false;
        }
    }
}

