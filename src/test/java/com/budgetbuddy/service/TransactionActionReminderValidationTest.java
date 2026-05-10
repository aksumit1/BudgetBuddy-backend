package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Integration Tests for TransactionActionService Reminder Validation Tests that reminder date
 * validation is called when creating/updating actions
 */
@ExtendWith(MockitoExtension.class)
class TransactionActionReminderValidationTest {

    @Mock private TransactionActionRepository actionRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private ReminderNotificationService reminderNotificationService;

    @InjectMocks private TransactionActionService transactionActionService;

    private UserTable testUser;
    private TransactionTable testTransaction;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        testTransaction = new TransactionTable();
        testTransaction.setTransactionId("transaction-123");
        testTransaction.setUserId("user-123");
        testTransaction.setAmount(new BigDecimal("100.0"));
        testTransaction.setTransactionDate("2024-12-01");
    }

    @Test
    void testCreateActionWithBothDatesValidatesReminderDate() {
        // Given: Action with both due date and reminder date
        final String dueDate = "2024-12-31";
        final String reminderDate = "2024-12-30T10:00:00Z";

        when(transactionRepository.findById("transaction-123"))
                .thenReturn(Optional.of(testTransaction));
        lenient().when(actionRepository.findById(any())).thenReturn(Optional.empty());
        doAnswer(
                        invocation -> {
                            final TransactionActionTable action = invocation.getArgument(0);
                            return null;
                        })
                .when(actionRepository)
                .save(any());

        // When: Create action
        final TransactionActionTable action =
                transactionActionService.createAction(
                        testUser,
                        "transaction-123",
                        "Test Action",
                        "Test Description",
                        dueDate,
                        reminderDate,
                        "MEDIUM");

        // Then: Validation should be called
        verify(reminderNotificationService, times(1)).validateReminderDate(reminderDate, dueDate);
        assertNotNull(action);
        assertEquals(reminderDate, action.getReminderDate());
        assertEquals(dueDate, action.getDueDate());
    }

    @Test
    void testCreateActionWithOnlyReminderDateNoValidationNeeded() {
        // Given: Action with only reminder date (no due date)
        final String reminderDate = "2024-12-30T10:00:00Z";

        when(transactionRepository.findById("transaction-123"))
                .thenReturn(Optional.of(testTransaction));
        lenient().when(actionRepository.findById(any())).thenReturn(Optional.empty());
        doAnswer(
                        invocation -> {
                            final TransactionActionTable action = invocation.getArgument(0);
                            return null;
                        })
                .when(actionRepository)
                .save(any());

        // When: Create action
        final TransactionActionTable action =
                transactionActionService.createAction(
                        testUser,
                        "transaction-123",
                        "Test Action",
                        "Test Description",
                        null, // No due date
                        reminderDate,
                        "MEDIUM");

        // Then: No validation needed (reminder date without due date is always valid)
        verify(reminderNotificationService, never()).validateReminderDate(any(), any());
        assertNotNull(action);
        assertEquals(reminderDate, action.getReminderDate());
        assertNull(action.getDueDate());
    }

    @Test
    void testCreateActionWithOnlyDueDateNoValidationNeeded() {
        // Given: Action with only due date (no reminder date)
        final String dueDate = "2024-12-31";

        when(transactionRepository.findById("transaction-123"))
                .thenReturn(Optional.of(testTransaction));
        lenient().when(actionRepository.findById(any())).thenReturn(Optional.empty());
        doAnswer(
                        invocation -> {
                            final TransactionActionTable action = invocation.getArgument(0);
                            return null;
                        })
                .when(actionRepository)
                .save(any());

        // When: Create action
        final TransactionActionTable action =
                transactionActionService.createAction(
                        testUser,
                        "transaction-123",
                        "Test Action",
                        "Test Description",
                        dueDate,
                        null, // No reminder date
                        "MEDIUM");

        // Then: No validation needed (due date without reminder date is always valid)
        verify(reminderNotificationService, never()).validateReminderDate(any(), any());
        assertNotNull(action);
        assertEquals(dueDate, action.getDueDate());
        assertNull(action.getReminderDate());
    }

    @Test
    void testUpdateActionWithBothDatesValidatesReminderDate() {
        // Given: Existing action and update with both dates
        final TransactionActionTable existingAction = new TransactionActionTable();
        existingAction.setActionId("action-123");
        existingAction.setUserId("user-123");
        existingAction.setTitle("Existing Action");
        existingAction.setDueDate("2024-12-31");
        existingAction.setReminderDate("2024-12-30T10:00:00Z");

        final String newDueDate = "2024-12-31";
        final String newReminderDate = "2024-12-29T10:00:00Z";

        when(actionRepository.findById("action-123")).thenReturn(Optional.of(existingAction));
        doAnswer(
                        invocation -> {
                            final TransactionActionTable action = invocation.getArgument(0);
                            return null;
                        })
                .when(actionRepository)
                .save(any());

        // When: Update action
        final TransactionActionTable updatedAction =
                transactionActionService.updateAction(
                        testUser,
                        "action-123",
                        null,
                        null,
                        newDueDate,
                        newReminderDate,
                        null,
                        null,
                        null);

        // Then: Validation should be called
        verify(reminderNotificationService, times(1))
                .validateReminderDate(newReminderDate, newDueDate);
        assertNotNull(updatedAction);
        assertEquals(newReminderDate, updatedAction.getReminderDate());
        assertEquals(newDueDate, updatedAction.getDueDate());
    }

    @Test
    void testUpdateActionWithReminderDateAfterDueDateStillValidates() {
        // Given: Reminder date after due date (user's choice, but not ideal)
        final TransactionActionTable existingAction = new TransactionActionTable();
        existingAction.setActionId("action-123");
        existingAction.setUserId("user-123");
        existingAction.setTitle("Existing Action");

        final String dueDate = "2024-12-31";
        final String reminderDate = "2025-01-01T10:00:00Z"; // After due date

        when(actionRepository.findById("action-123")).thenReturn(Optional.of(existingAction));
        doAnswer(
                        invocation -> {
                            final TransactionActionTable action = invocation.getArgument(0);
                            return null;
                        })
                .when(actionRepository)
                .save(any());

        // When: Update action
        final TransactionActionTable updatedAction =
                transactionActionService.updateAction(
                        testUser,
                        "action-123",
                        null,
                        null,
                        dueDate,
                        reminderDate,
                        null,
                        null,
                        null);

        // Then: Validation should still be called (logs warning but allows it)
        verify(reminderNotificationService, times(1)).validateReminderDate(reminderDate, dueDate);
        assertNotNull(updatedAction);
        assertEquals(reminderDate, updatedAction.getReminderDate());
        assertEquals(dueDate, updatedAction.getDueDate());
    }
}
