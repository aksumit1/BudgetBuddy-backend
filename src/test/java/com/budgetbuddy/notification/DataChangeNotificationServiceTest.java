package com.budgetbuddy.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for DataChangeNotificationService
 */
@ExtendWith(MockitoExtension.class)
class DataChangeNotificationServiceTest {

    @Mock
    private PushNotificationService pushNotificationService;

    private DataChangeNotificationService dataChangeNotificationService;

    private String testUserId;
    private String testTransactionId;
    private String testAccountId;
    private String testBudgetId;
    private String testGoalId;

    @BeforeEach
    void setUp() {
        testUserId = "test-user-id";
        testTransactionId = "test-transaction-id";
        testAccountId = "test-account-id";
        testBudgetId = "test-budget-id";
        testGoalId = "test-goal-id";
        
        // Manually construct service with mocked dependencies
        dataChangeNotificationService = new DataChangeNotificationService(pushNotificationService, true);
    }

    @Test
    void testNotifyTransactionCreated() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyTransactionCreated(testUserId, testTransactionId);

        // Then - wait for async operation
        Thread.sleep(100); // Give async operation time to complete

        verify(pushNotificationService, timeout(1000)).sendPushNotificationToAllDevices(
                eq(testUserId),
                eq("Data Updated"),
                eq(""),
                argThat(data -> {
                    Map<String, Object> map = (Map<String, Object>) data;
                    return "data_changed".equals(map.get("type"))
                            && "TRANSACTION_CREATED".equals(map.get("changeType"))
                            && Boolean.TRUE.equals(map.get("silent"))
                            && testTransactionId.equals(map.get("transactionId"))
                            && "transaction".equals(map.get("entityType"));
                })
        );
    }

    @Test
    void testNotifyTransactionUpdated() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyTransactionUpdated(testUserId, testTransactionId);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000)).sendPushNotificationToAllDevices(
                eq(testUserId),
                eq("Data Updated"),
                eq(""),
                argThat(data -> {
                    Map<String, Object> map = (Map<String, Object>) data;
                    return "TRANSACTION_UPDATED".equals(map.get("changeType"));
                })
        );
    }

    @Test
    void testNotifyAccountChanged() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyAccountChanged(testUserId, testAccountId);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000)).sendPushNotificationToAllDevices(
                eq(testUserId),
                eq("Data Updated"),
                eq(""),
                argThat(data -> {
                    Map<String, Object> map = (Map<String, Object>) data;
                    return "ACCOUNT_LINKED".equals(map.get("changeType"))
                            && testAccountId.equals(map.get("accountId"))
                            && "account".equals(map.get("entityType"));
                })
        );
    }

    @Test
    void testNotifyBudgetChanged() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyBudgetChanged(testUserId, testBudgetId);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000)).sendPushNotificationToAllDevices(
                eq(testUserId),
                eq("Data Updated"),
                eq(""),
                argThat(data -> {
                    Map<String, Object> map = (Map<String, Object>) data;
                    return "BUDGET_WARNING".equals(map.get("changeType"))
                            && testBudgetId.equals(map.get("budgetId"))
                            && "budget".equals(map.get("entityType"));
                })
        );
    }

    @Test
    void testNotifyGoalChanged() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyGoalChanged(testUserId, testGoalId);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000)).sendPushNotificationToAllDevices(
                eq(testUserId),
                eq("Data Updated"),
                eq(""),
                argThat(data -> {
                    Map<String, Object> map = (Map<String, Object>) data;
                    return "GOAL_PROGRESS".equals(map.get("changeType"))
                            && testGoalId.equals(map.get("goalId"))
                            && "goal".equals(map.get("entityType"));
                })
        );
    }

    @Test
    void testNotifyBatchTransactionsImported() throws InterruptedException {
        // Given
        int count = 10;
        when(pushNotificationService.sendPushNotificationToAllDevices(anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyBatchTransactionsImported(testUserId, count);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000)).sendPushNotificationToAllDevices(
                eq(testUserId),
                eq("Data Updated"),
                eq(""),
                argThat(data -> {
                    Map<String, Object> map = (Map<String, Object>) data;
                    return count == (Integer) map.get("count")
                            && Boolean.TRUE.equals(map.get("batch"))
                            && "transaction".equals(map.get("entityType"));
                })
        );
    }

    @Test
    void testNotificationFailureDoesNotThrow() {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Notification service unavailable"));

        // When/Then - should not throw
        assertDoesNotThrow(() -> {
            dataChangeNotificationService.notifyTransactionCreated(testUserId, testTransactionId);
            Thread.sleep(200); // Wait for async operation
        });
    }

    @Test
    void testNullUserIdHandledGracefully() {
        // When/Then - should not throw
        assertDoesNotThrow(() -> {
            dataChangeNotificationService.notifyTransactionCreated(null, testTransactionId);
            Thread.sleep(100);
        });

        // Should not call push service with null userId
        verify(pushNotificationService, never()).sendPushNotificationToAllDevices(
                isNull(), anyString(), anyString(), any());
    }

    @Test
    void testEmptyUserIdHandledGracefully() {
        // When/Then - should not throw
        assertDoesNotThrow(() -> {
            dataChangeNotificationService.notifyTransactionCreated("", testTransactionId);
            Thread.sleep(100);
        });

        // Should not call push service with empty userId
        verify(pushNotificationService, never()).sendPushNotificationToAllDevices(
                eq(""), anyString(), anyString(), any());
    }
}
