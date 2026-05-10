package com.budgetbuddy.compliance.gdpr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.compliance.AuditLogTable;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.BudgetTable;
import com.budgetbuddy.model.dynamodb.GoalTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.AuditLogRepository;
import com.budgetbuddy.repository.dynamodb.BudgetRepository;
import com.budgetbuddy.repository.dynamodb.GoalRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.budgetbuddy.service.aws.S3Service;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit Tests for GDPRComplianceService */
@ExtendWith(MockitoExtension.class)
class GDPRComplianceServiceTest {

    @Mock private UserRepository userRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private AccountRepository accountRepository;

    @Mock private BudgetRepository budgetRepository;

    @Mock private GoalRepository goalRepository;

    @Mock private AuditLogRepository auditLogRepository;

    @Mock private S3Service s3Service;

    @Mock private AuditLogService auditLogService;

    private GDPRComplianceService gdprComplianceService;
    private String testUserId;
    private UserTable testUser;

    @BeforeEach
    void setUp() {
        gdprComplianceService =
                new GDPRComplianceService(
                        userRepository,
                        transactionRepository,
                        accountRepository,
                        budgetRepository,
                        goalRepository,
                        auditLogRepository,
                        s3Service,
                        auditLogService);

        testUserId = "user-123";
        testUser = new UserTable();
        testUser.setUserId(testUserId);
        testUser.setEmail("test@example.com");
        testUser.setFirstName("John");
        testUser.setLastName("Doe");
    }

    @Test
    void testExportUserDataWithValidUserReturnsExport() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(transactionRepository.findByUserId(testUserId, 0, 10_000)).thenReturn(List.of());
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of());
        doNothing().when(auditLogService).logDataExport(anyString(), anyString());

        // When
        final GDPRComplianceService.GDPRDataExport export =
                gdprComplianceService.exportUserData(testUserId);

        // Then
        assertNotNull(export);
        assertEquals(testUserId, export.getUserId());
        assertNotNull(export.getExportId());
        assertNotNull(export.getExportDate());
        verify(auditLogService).logDataExport(testUserId, export.getExportId());
    }

    @Test
    void testExportUserDataWithAllDataExportsEverything() {
        // Given
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        final AccountTable account = new AccountTable();
        account.setAccountId("acc-123");
        final BudgetTable budget = new BudgetTable();
        budget.setBudgetId("budget-123");
        final GoalTable goal = new GoalTable();
        goal.setGoalId("goal-123");
        final AuditLogTable auditLog = new AuditLogTable();
        auditLog.setAuditLogId("log-123");

        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(transactionRepository.findByUserId(testUserId, 0, 10_000))
                .thenReturn(List.of(transaction));
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of(account));
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of(budget));
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of(goal));
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of(auditLog));
        doNothing().when(auditLogService).logDataExport(anyString(), anyString());

        // When
        final GDPRComplianceService.GDPRDataExport export =
                gdprComplianceService.exportUserData(testUserId);

        // Then
        assertNotNull(export);
        assertEquals(testUser, export.getUserData());
        assertEquals(1, export.getTransactions().size());
        assertEquals(1, export.getAccounts().size());
        assertEquals(1, export.getBudgets().size());
        assertEquals(1, export.getGoals().size());
        assertEquals(1, export.getAuditLogs().size());
    }

    @Test
    void testDeleteUserDataWithValidUserDeletesAllData() {
        // Given
        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        final AccountTable account = new AccountTable();
        account.setAccountId("acc-123");
        final BudgetTable budget = new BudgetTable();
        budget.setBudgetId("budget-123");
        final GoalTable goal = new GoalTable();
        goal.setGoalId("goal-123");

        when(transactionRepository.findByUserId(testUserId, 0, 10_000))
                .thenReturn(List.of(transaction));
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of(account));
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of(budget));
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of(goal));
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        doNothing().when(transactionRepository).delete(anyString());
        doNothing().when(budgetRepository).delete(anyString());
        doNothing().when(goalRepository).delete(anyString());
        doNothing().when(userRepository).save(any(UserTable.class));
        doNothing().when(auditLogService).logDataDeletion(anyString());

        // When
        gdprComplianceService.deleteUserData(testUserId);

        // Then
        verify(transactionRepository).delete("txn-123");
        verify(budgetRepository).delete("budget-123");
        verify(goalRepository).delete("goal-123");
        verify(userRepository).save(any(UserTable.class));
        verify(auditLogService).logDataDeletion(testUserId);
    }

    @Test
    void testExportDataPortableWithValidUserReturnsJSON() {
        // Given
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(transactionRepository.findByUserId(testUserId, 0, 10_000)).thenReturn(List.of());
        when(accountRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(budgetRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(goalRepository.findByUserId(testUserId)).thenReturn(List.of());
        when(auditLogRepository.findByUserIdAndDateRange(anyString(), anyLong(), anyLong()))
                .thenReturn(List.of());
        doNothing().when(auditLogService).logDataExport(anyString(), anyString());

        // When
        final String json = gdprComplianceService.exportDataPortable(testUserId);

        // Then
        assertNotNull(json);
        assertTrue(json.contains("userId"));
        assertTrue(json.contains("exportDate"));
    }

    @Test
    void testUpdateUserDataWithValidDataUpdatesUser() {
        // Given
        final UserTable updatedData = new UserTable();
        updatedData.setFirstName("Jane");
        updatedData.setLastName("Smith");
        updatedData.setEmail("newemail@example.com");

        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        doNothing().when(userRepository).save(any(UserTable.class));
        doNothing().when(auditLogService).logDataUpdate(anyString());

        // When
        gdprComplianceService.updateUserData(testUserId, updatedData);

        // Then
        verify(userRepository).save(any(UserTable.class));
        verify(auditLogService).logDataUpdate(testUserId);
    }

    @Test
    void testUpdateUserDataWithPartialDataUpdatesOnlyProvidedFields() {
        // Given
        final UserTable updatedData = new UserTable();
        updatedData.setFirstName("Jane");
        // lastName and email are null

        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        doNothing().when(userRepository).save(any(UserTable.class));
        doNothing().when(auditLogService).logDataUpdate(anyString());

        // When
        gdprComplianceService.updateUserData(testUserId, updatedData);

        // Then
        verify(userRepository).save(any(UserTable.class));
        verify(auditLogService).logDataUpdate(testUserId);
    }

    @Test
    void testReportBreachWithValidInputReportsBreach() {
        // Given
        final String breachType = "UNAUTHORIZED_ACCESS";
        final String details = "Data breach detected";
        final int affectedUsers = 10;
        doNothing().when(auditLogService).logBreach(any());

        // When
        gdprComplianceService.reportBreach(testUserId, breachType, details, affectedUsers);

        // Then
        verify(auditLogService).logBreach(any());
    }

    @Test
    void testRecordConsentWithValidInputRecordsConsent() {
        // Given
        final String consentType = "MARKETING";
        final boolean granted = true;
        final String purpose = "Email marketing";
        doNothing()
                .when(auditLogService)
                .logConsent(anyString(), anyString(), anyBoolean(), anyString());

        // When
        gdprComplianceService.recordConsent(testUserId, consentType, granted, purpose);

        // Then
        verify(auditLogService).logConsent(testUserId, consentType, granted, purpose);
    }

    @Test
    void testWithdrawConsentWithValidInputWithdrawsConsent() {
        // Given
        final String consentType = "MARKETING";
        doNothing().when(auditLogService).logConsentWithdrawal(anyString(), anyString());

        // When
        gdprComplianceService.withdrawConsent(testUserId, consentType);

        // Then
        verify(auditLogService).logConsentWithdrawal(testUserId, consentType);
    }

    @Test
    void testLogDataProcessingNotificationWithValidInputLogsNotification() {
        // Given
        final String processingPurpose = "Account management";
        final String legalBasis = "Contract";
        doNothing()
                .when(auditLogService)
                .logDataProcessingNotification(anyString(), anyString(), anyString());

        // When
        gdprComplianceService.logDataProcessingNotification(
                testUserId, processingPurpose, legalBasis);

        // Then
        verify(auditLogService)
                .logDataProcessingNotification(testUserId, processingPurpose, legalBasis);
    }

    @Test
    void testGDPRDataExportSettersAndGetters() {
        // Given
        final GDPRComplianceService.GDPRDataExport export = new GDPRComplianceService.GDPRDataExport();
        final String exportId = "export-123";
        final Instant exportDate = Instant.now();
        final UserTable userData = new UserTable();

        // When
        export.setExportId(exportId);
        export.setUserId(testUserId);
        export.setExportDate(exportDate);
        export.setUserData(userData);
        export.setTransactions(List.of());
        export.setAccounts(List.of());
        export.setBudgets(List.of());
        export.setGoals(List.of());
        export.setAuditLogs(List.of());

        // Then
        assertEquals(exportId, export.getExportId());
        assertEquals(testUserId, export.getUserId());
        assertEquals(exportDate, export.getExportDate());
        assertEquals(userData, export.getUserData());
        assertNotNull(export.getTransactions());
        assertNotNull(export.getAccounts());
        assertNotNull(export.getBudgets());
        assertNotNull(export.getGoals());
        assertNotNull(export.getAuditLogs());
    }
}
