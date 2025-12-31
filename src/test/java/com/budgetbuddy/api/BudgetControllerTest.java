package com.budgetbuddy.api;

import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.BudgetService;
import com.budgetbuddy.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for BudgetController
 * 
 */
@ExtendWith(MockitoExtension.class)
class BudgetControllerTest {

    @Mock
    private BudgetService budgetService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private BudgetController budgetController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");
    }

    @Test
    void testGetBudgets_WithValidUser_ReturnsBudgets() {
        // Given
        List<BudgetTable> mockBudgets = Arrays.asList(
                createBudget("budget-1"),
                createBudget("budget-2")
        );
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(budgetService.getBudgets(testUser)).thenReturn(mockBudgets);

        // When
        ResponseEntity<List<BudgetTable>> response = budgetController.getBudgets(userDetails);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void testGetBudgets_WithNullUserDetails_ThrowsException() {
        // When/Then - Exception thrown before userService is called
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.getBudgets(null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetBudgets_WithUserNotFound_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.empty());

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.getBudgets(userDetails));
        assertEquals(com.budgetbuddy.exception.ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudget_WithValidData_CreatesBudget() {
        // Given
        BudgetController.CreateBudgetRequest request = new BudgetController.CreateBudgetRequest();
        request.setCategory("FOOD");
        request.setMonthlyLimit(BigDecimal.valueOf(1000.00));

        BudgetTable mockBudget = createBudget("budget-1");
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(budgetService.createOrUpdateBudget(testUser, "FOOD", BigDecimal.valueOf(1000.00), null))
                .thenReturn(mockBudget);

        // When
        ResponseEntity<BudgetTable> response = budgetController.createOrUpdateBudget(userDetails, request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testCreateOrUpdateBudget_WithNullRequest_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        // Note: userService.findByEmail is not called because null request check happens first

        // When/Then - Exception thrown for null request
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.createOrUpdateBudget(userDetails, null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudget_WithNullLimit_ThrowsException() {
        // Given
        BudgetController.CreateBudgetRequest request = new BudgetController.CreateBudgetRequest();
        request.setCategory("FOOD");
        request.setMonthlyLimit(null);
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.createOrUpdateBudget(userDetails, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudget_WithZeroLimit_ThrowsException() {
        // Given
        BudgetController.CreateBudgetRequest request = new BudgetController.CreateBudgetRequest();
        request.setCategory("FOOD");
        request.setMonthlyLimit(BigDecimal.ZERO);
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.createOrUpdateBudget(userDetails, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudget_WithNegativeLimit_ThrowsException() {
        // Given
        BudgetController.CreateBudgetRequest request = new BudgetController.CreateBudgetRequest();
        request.setCategory("FOOD");
        request.setMonthlyLimit(BigDecimal.valueOf(-100));
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.createOrUpdateBudget(userDetails, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudget_WithNullCategory_ThrowsException() {
        // Given
        BudgetController.CreateBudgetRequest request = new BudgetController.CreateBudgetRequest();
        request.setCategory(null);
        request.setMonthlyLimit(BigDecimal.valueOf(1000.00));
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.createOrUpdateBudget(userDetails, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateOrUpdateBudget_WithEmptyCategory_ThrowsException() {
        // Given
        BudgetController.CreateBudgetRequest request = new BudgetController.CreateBudgetRequest();
        request.setCategory("");
        request.setMonthlyLimit(BigDecimal.valueOf(1000.00));
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.createOrUpdateBudget(userDetails, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteBudget_WithValidBudget_DeletesBudget() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        doNothing().when(budgetService).deleteBudget(testUser, "budget-1");

        // When
        ResponseEntity<Void> response = budgetController.deleteBudget(userDetails, "budget-1");

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(budgetService).deleteBudget(testUser, "budget-1");
    }

    @Test
    void testDeleteBudget_WithNullId_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        // Note: userService.findByEmail is not called because null ID check happens first

        // When/Then - Exception thrown for null ID
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.deleteBudget(userDetails, null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteBudget_WithEmptyId_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn("test@example.com");
        // Note: userService.findByEmail is not called because empty ID check happens first

        // When/Then - Exception thrown for empty ID
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.deleteBudget(userDetails, ""));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteBudget_WithNullUserDetails_ThrowsException() {
        // When/Then - Exception thrown before userService is called
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> budgetController.deleteBudget(null, "budget-1"));
        assertEquals(com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    // Helper methods
    private BudgetTable createBudget(final String id) {
        BudgetTable budget = new BudgetTable();
        budget.setBudgetId(id);
        budget.setUserId("user-123");
        budget.setCategory("FOOD");
        budget.setMonthlyLimit(BigDecimal.valueOf(1000.00));
        return budget;
    }
}

