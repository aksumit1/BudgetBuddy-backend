package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.model.dynamodb.TransactionActionTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionActionService;
import com.budgetbuddy.service.UserService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

/** Unit Tests for TransactionActionController */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionActionControllerTest {

    @Mock private TransactionActionService actionService;

    @Mock private UserService userService;

    @Mock private UserDetails userDetails;

    @InjectMocks private TransactionActionController controller;

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
        testAction.setIsCompleted(false);
        testAction.setPriority("MEDIUM");

        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com"))
                .thenReturn(java.util.Optional.of(testUser));
    }

    @Test
    void testGetActionsWithValidUserReturnsActions() {
        // Given
        final List<TransactionActionTable> mockActions = Arrays.asList(testAction);
        when(actionService.getActionsByTransactionId(testUser, testTransaction.getTransactionId()))
                .thenReturn(mockActions);

        // When
        final ResponseEntity<List<TransactionActionTable>> response =
                controller.getActions(userDetails, testTransaction.getTransactionId());

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());
        assertEquals("Test Action", response.getBody().get(0).getTitle());
    }

    @Test
    void testGetActionsWithNullUserDetailsThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () -> controller.getActions(null, testTransaction.getTransactionId()));
    }

    @Test
    void testGetActionsWithEmptyTransactionIdThrowsException() {
        // When/Then
        assertThrows(AppException.class, () -> controller.getActions(userDetails, ""));
    }

    @Test
    void testCreateActionWithValidRequestReturnsCreated() {
        // Given
        final TransactionActionController.CreateActionRequest request =
                new TransactionActionController.CreateActionRequest();
        request.setTitle("New Action");
        request.setDescription("Action description");
        request.setPriority("HIGH");

        // Updated to match the new method signature with actionId and plaidTransactionId
        when(actionService.createAction(
                        eq(testUser),
                        eq(testTransaction.getTransactionId()),
                        eq("New Action"),
                        eq("Action description"),
                        isNull(),
                        isNull(),
                        eq("HIGH"),
                        isNull(), // actionId
                        isNull())) // plaidTransactionId
                .thenReturn(testAction);

        // When
        final ResponseEntity<TransactionActionTable> response =
                controller.createAction(userDetails, testTransaction.getTransactionId(), request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Test Action", response.getBody().getTitle());
    }

    @Test
    void testCreateActionWithEmptyTitleThrowsException() {
        // Given
        final TransactionActionController.CreateActionRequest request =
                new TransactionActionController.CreateActionRequest();
        request.setTitle("");

        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        controller.createAction(
                                userDetails, testTransaction.getTransactionId(), request));
    }

    @Test
    void testUpdateActionWithValidRequestReturnsOk() {
        // Given
        final TransactionActionController.UpdateActionRequest request =
                new TransactionActionController.UpdateActionRequest();
        request.setTitle("Updated Action");
        request.setIsCompleted(true);

        final TransactionActionTable updatedAction = new TransactionActionTable();
        updatedAction.setActionId(testAction.getActionId());
        updatedAction.setTitle("Updated Action");
        updatedAction.setIsCompleted(true);

        when(actionService.updateAction(
                        eq(testUser),
                        eq(testAction.getActionId()),
                        eq("Updated Action"),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(true),
                        isNull(),
                        isNull()))
                .thenReturn(updatedAction);

        // When
        final ResponseEntity<TransactionActionTable> response =
                controller.updateAction(
                        userDetails,
                        testTransaction.getTransactionId(),
                        testAction.getActionId(),
                        request);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("Updated Action", response.getBody().getTitle());
        assertTrue(response.getBody().getIsCompleted());
    }

    @Test
    void testUpdateActionWithEmptyActionIdThrowsException() {
        // Given
        final TransactionActionController.UpdateActionRequest request =
                new TransactionActionController.UpdateActionRequest();

        // When/Then
        assertThrows(
                AppException.class,
                () ->
                        controller.updateAction(
                                userDetails, testTransaction.getTransactionId(), "", request));
    }

    @Test
    void testDeleteActionWithValidActionIdReturnsNoContent() {
        // Given
        doNothing().when(actionService).deleteAction(testUser, testAction.getActionId());

        // When
        final ResponseEntity<Void> response =
                controller.deleteAction(
                        userDetails, testTransaction.getTransactionId(), testAction.getActionId());

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(actionService, times(1)).deleteAction(testUser, testAction.getActionId());
    }

    @Test
    void testDeleteActionWithEmptyActionIdThrowsException() {
        // When/Then
        assertThrows(
                AppException.class,
                () -> controller.deleteAction(userDetails, testTransaction.getTransactionId(), ""));
    }
}
