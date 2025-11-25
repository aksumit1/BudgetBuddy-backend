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
 */
@ExtendWith(MockitoExtension.class)
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
        assertThrows(Exception.class, () -> transactionController.getTransactions(null, 0, 20));
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
        request.setCategory("FOOD");

        TransactionTable mockTransaction = createTransaction("tx-1");
        when(userService.findByEmail("test@example.com")).thenReturn(java.util.Optional.of(testUser));
        when(transactionService.createTransaction(any(), anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(mockTransaction);

        // When
        ResponseEntity<TransactionTable> response = transactionController.createTransaction(userDetails, request);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
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

