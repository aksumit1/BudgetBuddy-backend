package com.budgetbuddy.service;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.TransactionActionRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for TransactionActionService
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionActionServiceTest {

    @Mock
    private TransactionActionRepository actionRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionActionService actionService;

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
    void testCreateAction_WithValidInput_ReturnsAction() {
        // Given
        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.of(testTransaction));
        when(actionRepository.findById(anyString())).thenReturn(Optional.empty());
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When
        TransactionActionTable result = actionService.createAction(
                testUser,
                testTransaction.getTransactionId(),
                "New Action",
                "Description",
                null,
                null,
                "HIGH"
        );

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
    void testCreateAction_WithNullUser_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> actionService.createAction(
                null, testTransaction.getTransactionId(), "Title", null, null, null, null));
    }

    @Test
    void testCreateAction_WithEmptyTitle_ThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> actionService.createAction(
                testUser, testTransaction.getTransactionId(), "", null, null, null, null));
    }

    @Test
    void testCreateAction_WithNonExistentTransaction_ThrowsException() {
        // Given
        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.empty());

        // When/Then
        assertThrows(AppException.class, () -> actionService.createAction(
                testUser, testTransaction.getTransactionId(), "Title", null, null, null, null));
    }

    @Test
    void testCreateAction_WithTransactionBelongingToDifferentUser_ThrowsException() {
        // Given
        TransactionTable otherUserTransaction = new TransactionTable();
        otherUserTransaction.setTransactionId(testTransaction.getTransactionId());
        otherUserTransaction.setUserId("other-user-123");

        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.of(otherUserTransaction));

        // When/Then
        assertThrows(AppException.class, () -> actionService.createAction(
                testUser, testTransaction.getTransactionId(), "Title", null, null, null, null));
    }

    @Test
    void testUpdateAction_WithValidInput_ReturnsUpdatedAction() {
        // Given
        when(actionRepository.findById(testAction.getActionId()))
                .thenReturn(Optional.of(testAction));
        doNothing().when(actionRepository).save(any(TransactionActionTable.class));

        // When
        TransactionActionTable result = actionService.updateAction(
                testUser,
                testAction.getActionId(),
                "Updated Title",
                "Updated Description",
                null,
                null,
                true,
                "LOW"
        );

        // Then
        assertNotNull(result);
        assertEquals("Updated Title", result.getTitle());
        assertEquals("Updated Description", result.getDescription());
        assertTrue(result.getIsCompleted());
        assertEquals("LOW", result.getPriority());
        verify(actionRepository, times(1)).save(any(TransactionActionTable.class));
    }

    @Test
    void testUpdateAction_WithNonExistentAction_ThrowsException() {
        // Given
        when(actionRepository.findById(testAction.getActionId()))
                .thenReturn(Optional.empty());

        // When/Then
        assertThrows(AppException.class, () -> actionService.updateAction(
                testUser, testAction.getActionId(), "Title", null, null, null, null, null));
    }

    @Test
    void testUpdateAction_WithActionBelongingToDifferentUser_ThrowsException() {
        // Given
        TransactionActionTable otherUserAction = new TransactionActionTable();
        otherUserAction.setActionId(testAction.getActionId());
        otherUserAction.setUserId("other-user-123");

        when(actionRepository.findById(testAction.getActionId()))
                .thenReturn(Optional.of(otherUserAction));

        // When/Then
        assertThrows(AppException.class, () -> actionService.updateAction(
                testUser, testAction.getActionId(), "Title", null, null, null, null, null));
    }

    @Test
    void testDeleteAction_WithValidAction_DeletesAction() {
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
    void testDeleteAction_WithNonExistentAction_ThrowsException() {
        // Given
        when(actionRepository.findById(testAction.getActionId()))
                .thenReturn(Optional.empty());

        // When/Then
        assertThrows(AppException.class, () -> actionService.deleteAction(
                testUser, testAction.getActionId()));
    }

    @Test
    void testGetActionsByTransactionId_WithValidTransaction_ReturnsActions() {
        // Given
        List<TransactionActionTable> mockActions = Arrays.asList(testAction);
        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.of(testTransaction));
        when(actionRepository.findByTransactionId(testTransaction.getTransactionId()))
                .thenReturn(mockActions);

        // When
        List<TransactionActionTable> result = actionService.getActionsByTransactionId(
                testUser, testTransaction.getTransactionId());

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Test Action", result.get(0).getTitle());
    }

    @Test
    void testGetActionsByTransactionId_WithNonExistentTransaction_ReturnsEmptyList() {
        // Given
        when(transactionRepository.findById(testTransaction.getTransactionId()))
                .thenReturn(Optional.empty());
        when(actionRepository.findByTransactionId(testTransaction.getTransactionId()))
                .thenReturn(java.util.Collections.emptyList());

        // When
        List<TransactionActionTable> result = actionService.getActionsByTransactionId(
                testUser, testTransaction.getTransactionId());

        // Then - Should return empty list (transaction may not be synced yet)
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void testGetActionsByUserId_WithValidUser_ReturnsActions() {
        // Given
        List<TransactionActionTable> mockActions = Arrays.asList(testAction);
        when(actionRepository.findByUserId(testUser.getUserId()))
                .thenReturn(mockActions);

        // When
        List<TransactionActionTable> result = actionService.getActionsByUserId(testUser);

        // Then
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("Test Action", result.get(0).getTitle());
    }
}

