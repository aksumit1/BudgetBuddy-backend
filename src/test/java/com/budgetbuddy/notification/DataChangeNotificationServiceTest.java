package com.budgetbuddy.notification;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for DataChangeNotificationService */
@ExtendWith(MockitoExtension.class)
class DataChangeNotificationServiceTest {

    private static final String ENTITYTYPE = "entityType";
    private static final String CHANGETYPE = "changeType";

    @Mock private PushNotificationService pushNotificationService;

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
        dataChangeNotificationService =
                new DataChangeNotificationService(pushNotificationService, true);
    }

    @Test
    void testNotifyTransactionCreated() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(
                        anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyTransactionCreated(testUserId, testTransactionId);

        // Then - wait for async operation
        Thread.sleep(100); // Give async operation time to complete

        verify(pushNotificationService, timeout(1000))
                .sendPushNotificationToAllDevices(
                        eq(testUserId),
                        eq("Data Updated"),
                        eq(""),
                        argThat(
                                data -> {
                                    final Map<String, Object> map = (Map<String, Object>) data;
                                    return "data_changed".equals(map.get("type"))
                                            && "TRANSACTION_CREATED".equals(map.get(CHANGETYPE))
                                            && Boolean.TRUE.equals(map.get("silent"))
                                            && testTransactionId.equals(map.get("transactionId"))
                                            && "transaction".equals(map.get(ENTITYTYPE));
                                }));
    }

    @Test
    void testNotifyTransactionUpdated() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(
                        anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyTransactionUpdated(testUserId, testTransactionId);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000))
                .sendPushNotificationToAllDevices(
                        eq(testUserId),
                        eq("Data Updated"),
                        eq(""),
                        argThat(
                                data -> {
                                    final Map<String, Object> map = (Map<String, Object>) data;
                                    return "TRANSACTION_UPDATED".equals(map.get(CHANGETYPE));
                                }));
    }

    @Test
    void testNotifyAccountChanged() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(
                        anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyAccountChanged(testUserId, testAccountId);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000))
                .sendPushNotificationToAllDevices(
                        eq(testUserId),
                        eq("Data Updated"),
                        eq(""),
                        argThat(
                                data -> {
                                    final Map<String, Object> map = (Map<String, Object>) data;
                                    return "ACCOUNT_LINKED".equals(map.get(CHANGETYPE))
                                            && testAccountId.equals(map.get("accountId"))
                                            && "account".equals(map.get(ENTITYTYPE));
                                }));
    }

    @Test
    void testNotifyBudgetChanged() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(
                        anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyBudgetChanged(testUserId, testBudgetId);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000))
                .sendPushNotificationToAllDevices(
                        eq(testUserId),
                        eq("Data Updated"),
                        eq(""),
                        argThat(
                                data -> {
                                    final Map<String, Object> map = (Map<String, Object>) data;
                                    return "BUDGET_WARNING".equals(map.get(CHANGETYPE))
                                            && testBudgetId.equals(map.get("budgetId"))
                                            && "budget".equals(map.get(ENTITYTYPE));
                                }));
    }

    @Test
    void testNotifyGoalChanged() throws InterruptedException {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(
                        anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyGoalChanged(testUserId, testGoalId);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000))
                .sendPushNotificationToAllDevices(
                        eq(testUserId),
                        eq("Data Updated"),
                        eq(""),
                        argThat(
                                data -> {
                                    final Map<String, Object> map = (Map<String, Object>) data;
                                    return "GOAL_PROGRESS".equals(map.get(CHANGETYPE))
                                            && testGoalId.equals(map.get("goalId"))
                                            && "goal".equals(map.get(ENTITYTYPE));
                                }));
    }

    @Test
    void testNotifyBatchTransactionsImported() throws InterruptedException {
        // Given
        final int count = 10;
        when(pushNotificationService.sendPushNotificationToAllDevices(
                        anyString(), anyString(), anyString(), any()))
                .thenReturn(1);

        // When
        dataChangeNotificationService.notifyBatchTransactionsImported(testUserId, count);

        // Then
        Thread.sleep(100);
        verify(pushNotificationService, timeout(1000))
                .sendPushNotificationToAllDevices(
                        eq(testUserId),
                        eq("Data Updated"),
                        eq(""),
                        argThat(
                                data -> {
                                    final Map<String, Object> map = (Map<String, Object>) data;
                                    return count == (Integer) map.get("count")
                                            && Boolean.TRUE.equals(map.get("batch"))
                                            && "transaction".equals(map.get(ENTITYTYPE));
                                }));
    }

    @Test
    void testNotificationFailureDoesNotThrow() {
        // Given
        when(pushNotificationService.sendPushNotificationToAllDevices(
                        anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("Notification service unavailable"));

        // When/Then - should not throw
        assertDoesNotThrow(
                () -> {
                    dataChangeNotificationService.notifyTransactionCreated(
                            testUserId, testTransactionId);
                    Thread.sleep(200); // Wait for async operation
                });
    }

    @Test
    void testNullUserIdHandledGracefully() {
        // When/Then - should not throw
        assertDoesNotThrow(
                () -> {
                    dataChangeNotificationService.notifyTransactionCreated(null, testTransactionId);
                    Thread.sleep(100);
                });

        // Should not call push service with null userId
        verify(pushNotificationService, never())
                .sendPushNotificationToAllDevices(isNull(), anyString(), anyString(), any());
    }

    @Test
    void testEmptyUserIdHandledGracefully() {
        // When/Then - should not throw
        assertDoesNotThrow(
                () -> {
                    dataChangeNotificationService.notifyTransactionCreated("", testTransactionId);
                    Thread.sleep(100);
                });

        // Should not call push service with empty userId
        verify(pushNotificationService, never())
                .sendPushNotificationToAllDevices(eq(""), anyString(), anyString(), any());
    }
}
