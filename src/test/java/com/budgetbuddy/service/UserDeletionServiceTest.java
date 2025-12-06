package com.budgetbuddy.service;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.compliance.AuditLogTable;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for UserDeletionService
 * Tests user data deletion, Plaid integration deletion, and account deletion
 */
@ExtendWith(MockitoExtension.class)
class UserDeletionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BudgetRepository budgetRepository;

    @Mock
    private GoalRepository goalRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private UserDeletionService userDeletionService;

    private String testUserId;
    private UserTable testUser;
    private AccountTable testAccount;
    private TransactionTable testTransaction;
    private BudgetTable testBudget;
    private GoalTable testGoal;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID().toString();
        
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
        
        testAccount = new AccountTable();
        testAccount.setAccountId(UUID.randomUUID().toString());
        testAccount.setUserId(testUserId);
        testAccount.setPlaidItemId("plaid-item-123");
        testAccount.setPlaidAccountId("plaid-account-123");
        testAccount.setActive(true);
        
        testTransaction = new TransactionTable();
        testTransaction.setTransactionId(UUID.randomUUID().toString());
        testTransaction.setUserId(testUserId);
        
        testBudget = new BudgetTable();
        testBudget.setBudgetId(UUID.randomUUID().toString());
        testBudget.setUserId(testUserId);
        
        testGoal = new GoalTable();
        testGoal.setGoalId(UUID.randomUUID().toString());
        testGoal.setUserId(testUserId);
    }

    @Test
    void testDeleteAllUserData_WithValidUserId_DeletesAllData() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of(testAccount));
        when(transactionRepository.findByUserId(testUserId, anyInt(), anyInt())).thenReturn(List.of(testTransaction));
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of(testBudget));
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of(testGoal));
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(new AuditLogTable()));
        
        doNothing().when(accountRepository).save(any(AccountTable.class));
        doNothing().when(transactionRepository).batchDelete(anyList());
        doNothing().when(accountRepository).batchDelete(anyList());
        doNothing().when(budgetRepository).delete(anyString());
        doNothing().when(goalRepository).delete(anyString());
        doNothing().when(auditLogRepository).save(any(AuditLogTable.class));
        doNothing().when(auditLogService).logDataDeletion(anyString());

        // When
        userDeletionService.deleteAllUserData(testUserId);

        // Then
        verify(accountRepository, atLeastOnce()).findByUserId(testUserId);
        verify(transactionRepository, atLeastOnce()).findByUserId(testUserId, anyInt(), anyInt());
        verify(budgetRepository, times(1)).findByUserId(testUserId);
        verify(goalRepository, times(1)).findByUserId(testUserId);
        verify(auditLogService, times(1)).logDataDeletion(testUserId);
    }

    @Test
    void testDeleteAllUserData_WithNullUserId_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionService.deleteAllUserData(null);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteAllUserData_WithEmptyUserId_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionService.deleteAllUserData("");
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeletePlaidIntegration_WithValidUserId_DeletesPlaidData() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of(testAccount));
        when(transactionRepository.findByUserId(testUserId, anyInt(), anyInt())).thenReturn(List.of(testTransaction));
        
        doNothing().when(accountRepository).save(any(AccountTable.class));
        doNothing().when(transactionRepository).batchDelete(anyList());
        doNothing().when(accountRepository).batchDelete(anyList());
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), any(), any(), any(), any());

        // When
        userDeletionService.deletePlaidIntegration(testUserId);

        // Then
        verify(accountRepository, atLeastOnce()).findByUserId(testUserId);
        verify(transactionRepository, atLeastOnce()).findByUserId(testUserId, anyInt(), anyInt());
        verify(auditLogService, times(1)).logAction(eq(testUserId), eq("DELETE_PLAID_INTEGRATION"), anyString(), any(), any(), any(), any());
    }

    @Test
    void testDeletePlaidIntegration_WithNullUserId_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionService.deletePlaidIntegration(null);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteAccountCompletely_WithValidUserId_DeletesAccount() {
        // Given
        when(accountRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(transactionRepository.findByUserId(testUserId, anyInt(), anyInt())).thenReturn(Collections.emptyList());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(goalRepository.findByUserId(testUserId)).thenReturn(Collections.emptyList());
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());
        
        doNothing().when(userRepository).delete(anyString());
        doNothing().when(auditLogService).logDataDeletion(anyString());
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), any(), any(), any(), any());

        // When
        userDeletionService.deleteAccountCompletely(testUserId);

        // Then
        verify(userRepository, times(1)).delete(testUserId);
        verify(auditLogService, times(1)).logAction(eq(testUserId), eq("DELETE_ACCOUNT"), anyString(), any(), any(), any(), any());
    }

    @Test
    void testDeleteAccountCompletely_WithNullUserId_ThrowsException() {
        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionService.deleteAccountCompletely(null);
        });
        assertEquals(ErrorCode.INVALID_INPUT, exception.getErrorCode());
    }

    @Test
    void testDeleteAllUserData_WithException_ThrowsAppException() {
        // Given
        when(accountRepository.findByUserId(testUserId))
                .thenThrow(new RuntimeException("Database error"));

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionService.deleteAllUserData(testUserId);
        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }

    @Test
    void testDeletePlaidIntegration_WithException_ThrowsAppException() {
        // Given
        when(accountRepository.findByUserId(testUserId))
                .thenThrow(new RuntimeException("Database error"));

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionService.deletePlaidIntegration(testUserId);
        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }

    @Test
    void testDeleteAccountCompletely_WithException_ThrowsAppException() {
        // Given
        when(accountRepository.findByUserId(testUserId))
                .thenThrow(new RuntimeException("Database error"));

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> {
            userDeletionService.deleteAccountCompletely(testUserId);
        });
        assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
    }
}

