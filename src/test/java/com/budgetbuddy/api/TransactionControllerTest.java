package com.budgetbuddy.api;

import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.service.TransactionService;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for TransactionController
 * 
 */
@ExtendWith(MockitoExtension.class)
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
class TransactionControllerTest {

    @Mock
    private TransactionService transactionService;

    @Mock
    private UserService userService;

    @Mock
    private UserDetails userDetails;

    @InjectMocks
    private TransactionController transactionController;

    private UserTable testUser;

    @BeforeEach
    void setUp() {
        testUser = new UserTable();
        testUser.setUserId("user-123");
        testUser.setEmail("test@example.com");

        when(userDetails.getUsername()).thenReturn("test@example.com");
    }

    @Test
    void testGetTransactions_WithValidUser_ReturnsTransactions() {
        // Given
        List<TransactionTable> mockTransactions = Arrays.asList(
                createTransaction("tx-1"),
                createTransaction("tx-2")
        );
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(transactionService.getTransactions(testUser, 0, 20)).thenReturn(mockTransactions);

        // When
        ResponseEntity<List<TransactionTable>> response = transactionController.getTransactions(userDetails, 0, 20);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void testGetTransactions_WithNullUserDetails_ThrowsException() {
        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransactions(null, 0, 20));
        assertEquals(com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithNullUsername_ThrowsException() {
        // Given
        when(userDetails.getUsername()).thenReturn(null);

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransactions(userDetails, 0, 20));
        assertEquals(com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithNegativePage_ThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransactions(userDetails, -1, 20));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactions_WithInvalidPageSize_ThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then - Size too small
        com.budgetbuddy.exception.AppException exception1 = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransactions(userDetails, 0, 0));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception1.getErrorCode());

        // When/Then - Size too large
        com.budgetbuddy.exception.AppException exception2 = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransactions(userDetails, 0, 101));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception2.getErrorCode());
    }

    @Test
    void testGetTransactions_WithUserNotFound_ThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.empty());

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransactions(userDetails, 0, 20));
        assertEquals(com.budgetbuddy.exception.ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsInRange_WithNullUserDetails_ThrowsException() {
        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransactionsInRange(null, LocalDate.now().minusDays(7), LocalDate.now()));
        assertEquals(com.budgetbuddy.exception.ErrorCode.UNAUTHORIZED_ACCESS, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsInRange_WithInvalidDateRange_ThrowsException() {
        // Given
        LocalDate startDate = LocalDate.now();
        LocalDate endDate = LocalDate.now().minusDays(7); // End before start
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransactionsInRange(userDetails, startDate, endDate));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_DATE_RANGE, exception.getErrorCode());
    }

    @Test
    void testGetTransaction_WithNullId_ThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransaction(userDetails, null, null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransaction_WithEmptyId_ThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.getTransaction(userDetails, "", null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateTransaction_WithNullRequest_ThrowsException() {
        // Given
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.createTransaction(userDetails, null));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateTransaction_WithNullAmount_ThrowsException() {
        // Given
        TransactionController.CreateTransactionRequest request = new TransactionController.CreateTransactionRequest();
        request.setTransactionDate(LocalDate.now());
        request.setCategoryPrimary("dining");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.createTransaction(userDetails, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateTransaction_WithNullDate_ThrowsException() {
        // Given
        TransactionController.CreateTransactionRequest request = new TransactionController.CreateTransactionRequest();
        request.setAmount(BigDecimal.valueOf(100.00));
        request.setCategoryPrimary("dining");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.createTransaction(userDetails, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testCreateTransaction_WithNullCategory_ThrowsException() {
        // Given
        TransactionController.CreateTransactionRequest request = new TransactionController.CreateTransactionRequest();
        request.setAmount(BigDecimal.valueOf(100.00));
        request.setTransactionDate(LocalDate.now());
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));

        // When/Then
        com.budgetbuddy.exception.AppException exception = assertThrows(
                com.budgetbuddy.exception.AppException.class,
                () -> transactionController.createTransaction(userDetails, request));
        assertEquals(com.budgetbuddy.exception.ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testGetTransactionsInRange_WithValidRange_ReturnsTransactions() {
        // Given
        LocalDate startDate = LocalDate.now().minusDays(7);
        LocalDate endDate = LocalDate.now();
        List<TransactionTable> mockTransactions = Arrays.asList(createTransaction("tx-1"));

        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(transactionService.getTransactionsInRange(testUser, startDate, endDate)).thenReturn(mockTransactions);

        // When
        ResponseEntity<List<TransactionTable>> response =
                transactionController.getTransactionsInRange(userDetails, startDate, endDate);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    void testCreateTransaction_WithValidData_CreatesTransaction() {
        // Given
        TransactionController.CreateTransactionRequest request = new TransactionController.CreateTransactionRequest();
        request.setAccountId("account-123");
        request.setAmount(BigDecimal.valueOf(100.00));
        request.setTransactionDate(LocalDate.now());
        request.setDescription("Test transaction");
        request.setCategoryPrimary("dining");
        request.setCategoryDetailed("dining");

        TransactionTable mockTransaction = createTransaction("tx-1");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        // Updated to match the full 22-parameter method signature: user, accountId, amount, transactionDate, description, categoryPrimary, categoryDetailed, importerCategoryPrimary, importerCategoryDetailed, transactionId, notes, plaidAccountId, plaidTransactionId, transactionType, currencyCode, importSource, importBatchId, importFileName, reviewStatus, merchantName, paymentChannel, userName
        when(transactionService.createTransaction(
                eq(testUser), eq("account-123"), any(BigDecimal.class), any(LocalDate.class), 
                any(), anyString(), any(), any(), any(), any(), any(), any(), any(), any(), 
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockTransaction);

        // When
        ResponseEntity<TransactionTable> response = transactionController.createTransaction(userDetails, request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody(), "Response body should not be null");
        assertEquals("tx-1", response.getBody().getTransactionId());
    }

    // Helper methods
    private TransactionTable createTransaction(final String id) {
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(id);
        transaction.setUserId("user-123");
        transaction.setAmount(BigDecimal.valueOf(100.00));
        transaction.setTransactionDate(LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE));
        return transaction;
    }
}

