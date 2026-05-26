package com.budgetbuddy.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for TransactionActionService */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionActionServiceTest {

    private static final String TITLE = "Title";

    @Mock private TransactionActionRepository actionRepository;

    @Mock private TransactionRepository transactionRepository;

    @InjectMocks private TransactionActionService actionService;

    private UserTable testUser;
    private TransactionTable testTransaction;
    private TransactionActionTable testAction;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        testTransaction = new TransactionTable();
        testTransaction.setTransactionId("tx-123");
        testTransaction.setUserId(testUser.getUserId());

        testAction = new TransactionActionTable();
        testAction.setActionId("action-123");
        testAction.setTransactionId(testTransaction.getTransactionId());
        testAction.setUserId(testUser.getUserId());
        testAction.setTitle("Test Action");
        testAction.setDescription("Test Description");
        testAction.setIsCompleted(false);
        testAction.setPriority("MEDIUM");
        testAction.setCreatedAt(Instant.now());
        testAction.setUpdatedAt(Instant.now());
    }

    @Test
    void testCreateActionWithValidInputReturnsAction() {
        // Given
        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.of(testTransaction));
        when(actionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When
        final TransactionActionTable result =
                actionService.createAction(
                        testUser,
                        testTransaction.getTransactionId(),
                        "New Action",
                        "Description",
                        null,
                        null,
                        "HIGH");

        // Then
        assertNotNull(result);
        assertEquals("New Action", result.getTitle());
        assertEquals("Description", result.getDescription());
        assertEquals("HIGH", result.getPriority());
        assertFalse(result.getIsCompleted());
        assertEquals(testUser.getUserId(), result.getUserId());
        assertEquals(testTransaction.getTransactionId(), result.getTransactionId());
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    void testCreateActionWithNullUserThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.createAction(
                                null,
                                testTransaction.getTransactionId(),
                                TITLE,
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void testCreateActionWithEmptyTitleThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.createAction(
                                testUser,
                                testTransaction.getTransactionId(),
                                "",
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void testCreateActionWithNonExistentTransactionThrowsException() {
        // Given
        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.empty());

        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.createAction(
                                testUser,
                                testTransaction.getTransactionId(),
                                TITLE,
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void testCreateActionWithTransactionBelongingToDifferentUserThrowsException() {
        // Given
        final TransactionTable otherUserTransaction = new TransactionTable();
        otherUserTransaction.setTransactionId(testTransaction.getTransactionId());
        otherUserTransaction.setUserId("other-user-123");

        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.of(otherUserTransaction));

        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.createAction(
                                testUser,
                                testTransaction.getTransactionId(),
                                TITLE,
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void testUpdateActionWithValidInputReturnsUpdatedAction() {
        // Given
        when(actionRepository.findById(testAction.getActionId()))
                .thenReturn(Optional.of(testAction));
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When
        final TransactionActionTable result =
                actionService.updateAction(
                        testUser,
                        testAction.getActionId(),
                        "Updated Title",
                        "Updated Description",
                        null,
                        null,
                        true,
                        "LOW",
                        null);

        // Then
        assertNotNull(result);
        assertEquals("Updated Title", result.getTitle());
        assertEquals("Updated Description", result.getDescription());
        assertTrue(result.getIsCompleted());
        assertEquals("LOW", result.getPriority());
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    void testUpdateActionWithNonExistentActionThrowsException() {
        // Given
        when(actionRepository.findById(testAction.getActionId())).thenReturn(Optional.empty());

        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.updateAction(
                                testUser,
                                testAction.getActionId(),
                                TITLE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void testUpdateActionWithActionBelongingToDifferentUserThrowsException() {
        // Given
        final TransactionActionTable otherUserAction = new TransactionActionTable();
        otherUserAction.setActionId(testAction.getActionId());
        otherUserAction.setUserId("other-user-123");

        when(actionRepository.findById(testAction.getActionId()))
                .thenReturn(Optional.of(otherUserAction));

        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        actionService.updateAction(
                                testUser,
                                testAction.getActionId(),
                                TITLE,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null));
    }

    @Test
    void testDeleteActionWithValidActionDeletesAction() {
        // Given
        when(actionRepository.findById(testAction.getActionId()))
                .thenReturn(Optional.of(testAction));
        doNothing().when(actionRepository).delete(testAction.getActionId());

        // When
        actionService.deleteAction(testUser, testAction.getActionId());

        // Then
        verify(actionRepository, times(1)).delete(testAction.getActionId());
    }

    @Test
    void testDeleteActionWithNonExistentActionThrowsException() {
        // Given
        when(actionRepository.findById(testAction.getActionId())).thenReturn(Optional.empty());

        // When/Then
        assertThrows(
                AppException.class,
                () -> actionService.deleteAction(testUser, testAction.getActionId()));
    }

    @Test
    void testGetActionsByTransactionIdWithValidTransactionReturnsActions() {
        // Given
        final List<TransactionActionTable> mockActions = Arrays.asList(testAction);
        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.of(testTransaction));
        when(actionRepository.findByTransactionId(testTransaction.getTransactionId()))
                .thenReturn(mockActions);

        // When
        final List<TransactionActionTable> result =
                actionService.getActionsByTransactionId(
                        testUser, testTransaction.getTransactionId());

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Test Action", result.getFirst().getTitle());
    }

    @Test
    void testGetActionsByTransactionIdWithNonExistentTransactionReturnsEmptyList() {
        // Given
        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.empty());
        when(actionRepository.findByTransactionId(testTransaction.getTransactionId()))
                .thenReturn(java.util.Collections.emptyList());

        // When
        final List<TransactionActionTable> result =
                actionService.getActionsByTransactionId(
                        testUser, testTransaction.getTransactionId());

        // Then - Should return empty list (transaction may not be synced yet)
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetActionsByUserIdWithValidUserReturnsActions() {
        // Given
        final List<TransactionActionTable> mockActions = Arrays.asList(testAction);
        when(actionRepository.findByUserId(testUser.getUserId())).thenReturn(mockActions);

        // When
        final List<TransactionActionTable> result = actionService.getActionsByUserId(testUser);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Test Action", result.getFirst().getTitle());
    }
}
