package com.budgetbuddy.compliance;

import com.budgetbuddy.model.dynamodb.*;
import com.budgetbuddy.repository.dynamodb.*;
import com.budgetbuddy.service.aws.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for GdprService
 */
@ExtendWith(MockitoExtension.class)
class GdprServiceTest {

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
    private S3Service s3Service;

    private GdprService gdprService;
    private String testUserId;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        gdprService = new GdprService(
                userRepository,
                accountRepository,
                transactionRepository,
                budgetRepository,
                goalRepository,
                auditLogRepository,
                s3Service
        );
        
        testUserId = "user-123";
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
        testUser.setEnabled(true);
        testUser.setEmailVerified(true);
        testUser.setCreatedAt(Instant.now());
    }

    @Test
    void testExportUserData_WithValidUser_ReturnsDownloadUrl() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(transactionRepository.findByUserId(testUserId, 0, 10000)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(s3Service.uploadFileInfrequentAccess(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                .thenReturn("s3-key");
        when(s3Service.getPresignedUrl(anyString(), anyInt())).thenReturn("https://s3.example.com/download");

        // When
        String downloadUrl = gdprService.exportUserData(testUserId);

        // Then
        assertNotNull(downloadUrl);
        assertEquals("https://s3.example.com/download", downloadUrl);
        verify(s3Service).uploadFileInfrequentAccess(anyString(), any(), anyInt(), anyString());
        verify(s3Service).getPresignedUrl(anyString(), eq(7 * 24 * 60));
    }

    @Test
    void testExportUserData_WithNonExistentUser_ThrowsException() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.empty());

        // When/Then
        assertThrows(IllegalArgumentException.class, () -> gdprService.exportUserData(testUserId));
    }

    @Test
    void testExportUserData_WithUserData_ExportsAllData() {
        // Given
        AccountTable account = new AccountTable();
        account.setAccountId("acc-123");
        account.setAccountName("Test Account");
        account.setBalance(BigDecimal.valueOf(1000));
        
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setAmount(BigDecimal.valueOf(50));
        transaction.setDescription("Test transaction");
        
        BudgetTable budget = new BudgetTable();
        budget.setBudgetId("budget-123");
        budget.setCategory("GROCERIES");
        budget.setMonthlyLimit(BigDecimal.valueOf(500));
        
        GoalTable goal = new GoalTable();
        goal.setGoalId("goal-123");
        goal.setName("Test Goal");
        goal.setTargetAmount(BigDecimal.valueOf(10000));
        
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of(account));
        when(transactionRepository.findByUserId(testUserId, 0, 10000)).thenReturn(List.of(transaction));
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of(budget));
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of(goal));
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(s3Service.uploadFileInfrequentAccess(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                .thenReturn("s3-key");
        when(s3Service.getPresignedUrl(anyString(), anyInt())).thenReturn("https://s3.example.com/download");

        // When
        String downloadUrl = gdprService.exportUserData(testUserId);

        // Then
        assertNotNull(downloadUrl);
        verify(accountRepository).findByUserId(testUserId);
        verify(transactionRepository).findByUserId(testUserId, 0, 10000);
        verify(budgetRepository).findByUserId(testUserId);
        verify(goalRepository).findByUserId(testUserId);
    }

    @Test
    void testDeleteUserData_WithValidUser_DeletesAllData() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        // exportUserData uses limit 10000, deleteUserData uses limit 1000
        when(transactionRepository.findByUserId(testUserId, 0, 10000)).thenReturn(List.of());
        when(transactionRepository.findByUserId(testUserId, 0, 1000)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(s3Service.uploadFileInfrequentAccess(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                .thenReturn("s3-key");
        when(s3Service.getPresignedUrl(anyString(), anyInt())).thenReturn("https://s3.example.com/archive");
        doNothing().when(userRepository).delete(testUserId);

        // When
        gdprService.deleteUserData(testUserId);

        // Then
        verify(userRepository).delete(testUserId);
        verify(s3Service).uploadFileInfrequentAccess(anyString(), any(), anyLong(), anyString());
    }

    @Test
    void testDeleteUserData_WithTransactions_DeletesInBatches() {
        // Given
        TransactionTable txn1 = new TransactionTable();
        txn1.setTransactionId("txn-1");
        TransactionTable txn2 = new TransactionTable();
        txn2.setTransactionId("txn-2");
        
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(transactionRepository.findByUserId(testUserId, 0, 1000)).thenReturn(List.of(txn1, txn2));
        when(transactionRepository.findByUserId(testUserId, 2, 1000)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(s3Service.uploadFileInfrequentAccess(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                .thenReturn("s3-key");
        when(s3Service.getPresignedUrl(anyString(), anyInt())).thenReturn("https://s3.example.com/archive");
        doNothing().when(transactionRepository).delete(anyString());
        doNothing().when(userRepository).delete(testUserId);

        // When
        gdprService.deleteUserData(testUserId);

        // Then
        verify(transactionRepository).delete("txn-1");
        verify(transactionRepository).delete("txn-2");
    }

    @Test
    void testDeleteUserData_WithAccounts_MarksAsInactive() {
        // Given
        AccountTable account = new AccountTable();
        account.setAccountId("acc-123");
        account.setActive(true);
        
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of(account));
        when(transactionRepository.findByUserId(testUserId, 0, 1000)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong())).thenReturn(List.of());
        when(s3Service.uploadFileInfrequentAccess(anyString(), any(ByteArrayInputStream.class), anyLong(), anyString()))
                .thenReturn("s3-key");
        when(s3Service.getPresignedUrl(anyString(), anyInt())).thenReturn("https://s3.example.com/archive");
        doNothing().when(accountRepository).save(any(AccountTable.class));
        doNothing().when(userRepository).delete(testUserId);

        // When
        gdprService.deleteUserData(testUserId);

        // Then
        verify(accountRepository).save(argThat(acc -> !acc.getActive()));
    }

    @Test
    void testDeleteUserData_WithException_ThrowsRuntimeException() {
        // Given
        when(userRepository.findById(testUserId)).thenThrow(new RuntimeException("Database error"));

        // When/Then
        assertThrows(RuntimeException.class, () -> gdprService.deleteUserData(testUserId));
    }
}

