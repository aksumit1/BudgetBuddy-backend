package com.budgetbuddy.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailRequest;
import software.amazon.awssdk.services.ses.model.SendTemplatedEmailResponse;

/**
 * Unit Tests for EmailNotificationService Tests email sending functionality including templated
 * emails
 */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock private SesClient sesClient;

    @Mock private ObjectMapper objectMapper;

    private EmailNotificationService emailService;
    private String testFromEmail = "noreply@budgetbuddy.com";
    private String testToEmail = "user@example.com";
    private String testUserId = "user-123";

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        emailService =
                new EmailNotificationService(sesClient, testFromEmail, objectMapper, true, null);

        // Set up log appender to capture log events for verification
        logger = (Logger) LoggerFactory.getLogger(EmailNotificationService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @Test
    void testSendEmailWithPlainEmailSendsSuccessfully() {
        // Given
        final String subject = "Test Subject";
        final String body = "<html><body>Test Body</body></html>";

        final SendEmailResponse response =
                SendEmailResponse.builder().messageId("test-message-id").build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(response);

        // When
        final boolean result =
                emailService.sendEmail(testUserId, testToEmail, subject, body, null, null);

        // Then
        assertTrue(result, "Email should be sent successfully");
        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void testSendEmailWithTemplateSendsTemplatedEmail() throws Exception {
        // Given
        final String templateId = "welcome-template";
        final Map<String, Object> templateData = new HashMap<>();
        templateData.put("name", "John");
        templateData.put("code", "123456");

        final String templateDataJson = "{\"name\":\"John\",\"code\":\"123456\"}";
        when(objectMapper.writeValueAsString(templateData)).thenReturn(templateDataJson);

        final SendTemplatedEmailResponse response =
                SendTemplatedEmailResponse.builder().messageId("test-message-id").build();
        when(sesClient.sendTemplatedEmail(any(SendTemplatedEmailRequest.class)))
                .thenReturn(response);

        // When
        final boolean result =
                emailService.sendEmail(
                        testUserId, testToEmail, "Subject", "Body", templateId, templateData);

        // Then
        assertTrue(result, "Templated email should be sent successfully");
        verify(sesClient, times(1)).sendTemplatedEmail(any(SendTemplatedEmailRequest.class));
        verify(sesClient, never()).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void testSendEmailWithSESExceptionReturnsFalse() {
        // Given
        when(sesClient.sendEmail(any(SendEmailRequest.class)))
                .thenThrow(new RuntimeException("SES error"));

        // When
        final boolean result =
                emailService.sendEmail(testUserId, testToEmail, "Subject", "Body", null, null);

        // Then
        assertFalse(result, "Should return false on error");
        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
    }

    @Test
    void testSendEmailWithTemplateExceptionReturnsFalse() throws Exception {
        // Given
        final String templateId = "template-id";
        final Map<String, Object> templateData = new HashMap<>();
        when(objectMapper.writeValueAsString(templateData))
                .thenThrow(new RuntimeException("JSON error"));
        // Note: sendTemplatedEmail won't be called if writeValueAsString throws

        // When
        final boolean result =
                emailService.sendEmail(
                        testUserId, testToEmail, "Subject", "Body", templateId, templateData);

        // Then
        assertFalse(result, "Should return false on template error");

        // Verify logging behavior - should log ERROR when JSON serialization fails
        final List<ILoggingEvent> logEvents = logAppender.list;
        final long errorLogs =
                logEvents.stream().filter(event -> event.getLevel() == Level.ERROR).count();

        assertEquals(1, errorLogs, "Should log ERROR when template JSON serialization fails");

        // Verify ERROR log contains expected message
        // Use getFormattedMessage() to get the actual formatted message, not the template
        final boolean foundErrorLog =
                logEvents.stream()
                        .anyMatch(
                                event ->
                                        event.getLevel() == Level.ERROR
                                                && event.getFormattedMessage()
                                                        .contains("Failed to send templated email")
                                                && event.getFormattedMessage()
                                                        .contains("JSON error"));
        assertTrue(foundErrorLog, "Should log ERROR with template failure message");
    }

    @Test
    void testSendEmailWithEmptyTemplateIdUsesPlainEmail() {
        // Given
        final SendEmailResponse response =
                SendEmailResponse.builder().messageId("test-message-id").build();
        when(sesClient.sendEmail(any(SendEmailRequest.class))).thenReturn(response);

        // When
        final boolean result =
                emailService.sendEmail(testUserId, testToEmail, "Subject", "Body", "", null);

        // Then
        assertTrue(result, "Should use plain email when template ID is empty");
        verify(sesClient, times(1)).sendEmail(any(SendEmailRequest.class));
        verify(sesClient, never()).sendTemplatedEmail(any(SendTemplatedEmailRequest.class));
    }
}
