package com.budgetbuddy.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for EmailNotificationService
 * Tests email sending functionality including templated emails
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private SesClient sesClient;

    @Mock
    private ObjectMapper objectMapper;

    private EmailNotificationService emailService;
    private String testFromEmail = "noreply@budgetbuddy.com";
    private String testToEmail = "user@example.com";
    private String testUserId = "user-123";
    
    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        emailService = new EmailNotificationService(sesClient, testFromEmail, objectMapper);
        
        // Set up log appender to capture log events for verification
        logger = (Logger) LoggerFactory.getLogger(EmailNotificationService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @Test
    void testSendEmail_WithPlainEmail_SendsSuccessfully() {
        // Given
        String subject = "Test Subject";
        String body = "<html><body>Test Body</body></html>";
        
        SendEmailResponse response = SendEmailResponse.builder()
                .messageId("test-message-id")
                .build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(response);

        // When
        boolean result = emailService.sendEmail(testUserId, testToEmail, subject, body, null, null);

        // Then
        assertTrue(result, "Email should be sent successfully");
        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void testSendEmail_WithTemplate_SendsTemplatedEmail() throws Exception {
        // Given
        String templateId = "welcome-template";
        Map<String, Object> templateData = new HashMap<>();
        templateData.put("name", "John");
        templateData.put("code", "123456");
        
        String templateDataJson = "{\"name\":\"John\",\"code\":\"123456\"}";
        when(objectMapper.writeValueAsString(templateData)).thenReturn(templateDataJson);
        
        SendTemplatedEmailResponse response = SendTemplatedEmailResponse.builder()
                .messageId("test-message-id")
                .build();
        when(sesClient.sendTemplatedEmail(any(SendTemplatedEmailRequest.class))).thenReturn(response);

        // When
        boolean result = emailService.sendEmail(testUserId, testToEmail, "Subject", "Body", templateId, templateData);

        // Then
        assertTrue(result, "Templated email should be sent successfully");
        verify(sesClient, times(1)).sendTemplatedEmail(any(SendTemplatedEmailRequest.class));
        verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void testSendEmail_WithSESException_ReturnsFalse() {
        // Given
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(new RuntimeException("SES error"));

        // When
        boolean result = emailService.sendEmail(testUserId, testToEmail, "Subject", "Body", null, null);

        // Then
        assertFalse(result, "Should return false on error");
        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void testSendEmail_WithTemplateException_ReturnsFalse() throws Exception {
        // Given
        String templateId = "template-id";
        Map<String, Object> templateData = new HashMap<>();
        when(objectMapper.writeValueAsString(templateData))
                .thenThrow(new RuntimeException("JSON error"));
        // Note: sendTemplatedEmail won't be called if writeValueAsString throws

        // When
        boolean result = emailService.sendEmail(testUserId, testToEmail, "Subject", "Body", templateId, templateData);

        // Then
        assertFalse(result, "Should return false on template error");
        
        // Verify logging behavior - should log ERROR when JSON serialization fails
        List<ILoggingEvent> logEvents = logAppender.list;
        long errorLogs = logEvents.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .count();
        
        assertEquals(1, errorLogs, "Should log ERROR when template JSON serialization fails");
        
        // Verify ERROR log contains expected message
        // Use getFormattedMessage() to get the actual formatted message, not the template
        boolean foundErrorLog = logEvents.stream()
                .anyMatch(event -> event.getLevel() == Level.ERROR 
                        && event.getFormattedMessage().contains("Failed to send templated email")
                        && event.getFormattedMessage().contains("JSON error"));
        assertTrue(foundErrorLog, "Should log ERROR with template failure message");
    }

    @Test
    void testSendEmail_WithEmptyTemplateId_UsesPlainEmail() {
        // Given
        SendEmailResponse response = SendEmailResponse.builder()
                .messageId("test-message-id")
                .build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(response);

        // When
        boolean result = emailService.sendEmail(testUserId, testToEmail, "Subject", "Body", "", null);

        // Then
        assertTrue(result, "Should use plain email when template ID is empty");
        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
        verify(sesClient, never()).sendTemplatedEmail(any(SendTemplatedEmailRequest.class));
    }
}

