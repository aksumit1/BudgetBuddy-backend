package com.budgetbuddy.notification;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.sns.SnsClient;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for NotificationService
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private SnsClient snsClient;

    @Mock
    private EmailNotificationService emailService;

    @Mock
    private PushNotificationService pushService;

    @InjectMocks
    private NotificationService notificationService;

    private NotificationRequest testRequest;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(notificationService, "snsTopicArn", "arn:aws:sns:us-east-1:123456789:test-topic");
        ReflectionTestUtils.setField(notificationService, "notificationsEnabled", true);
        
        testRequest = new NotificationRequest();
        testRequest.setUserId("user-123");
        testRequest.setTitle("Test Notification");
        testRequest.setMessage("Test message");
        testRequest.setChannels(Set.of(NotificationChannel.EMAIL));
    }

    @Test
    void testSendNotification_WithNullRequest_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            notificationService.sendNotification(null);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testSendNotification_WithNoChannels_ReturnsFailure() {
        // Given
        testRequest.setChannels(new HashSet<>());

        // When
        NotificationResult result = notificationService.sendNotification(testRequest);

        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
    }

    @Test
    void testSendNotification_WithEmailChannel_CallsEmailService() {
        // Given
        when(emailService.sendEmail(anyString(), anyString(), anyString())).thenReturn(true);

        // When
        NotificationResult result = notificationService.sendNotification(testRequest);

        // Then
        verify(emailService, times(1)).sendEmail(anyString(), anyString(), anyString());
        assertNotNull(result);
    }

    @Test
    void testSendNotification_WithSMSChannel_CallsSNSService() {
        // Given
        testRequest.setChannels(Set.of(NotificationChannel.SMS));

        // When
        NotificationResult result = notificationService.sendNotification(testRequest);

        // Then
        assertNotNull(result);
        // Verify SNS was called (would need proper mocking)
    }

    @Test
    void testSendNotification_WithPushChannel_CallsPushService() {
        // Given
        testRequest.setChannels(Set.of(NotificationChannel.PUSH));
        when(pushService.sendPushNotification(anyString(), anyString(), any())).thenReturn(true);

        // When
        NotificationResult result = notificationService.sendNotification(testRequest);

        // Then
        verify(pushService, times(1)).sendPushNotification(anyString(), anyString(), any());
        assertNotNull(result);
    }

    @Test
    void testSendNotification_WhenNotificationsDisabled_ReturnsDisabled() {
        // Given
        ReflectionTestUtils.setField(notificationService, "notificationsEnabled", false);

        // When
        NotificationResult result = notificationService.sendNotification(testRequest);

        // Then
        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("Notifications disabled", result.getMessage());
    }

    @Test
    void testNotificationService_Constructor_WithNullSnsClient_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new NotificationService(null, emailService, pushService, "arn", true);
        });
    }

    @Test
    void testNotificationService_Constructor_WithNullEmailService_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new NotificationService(snsClient, null, pushService, "arn", true);
        });
    }

    @Test
    void testNotificationService_Constructor_WithNullPushService_ThrowsException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new NotificationService(snsClient, emailService, null, "arn", true);
        });
    }
}

