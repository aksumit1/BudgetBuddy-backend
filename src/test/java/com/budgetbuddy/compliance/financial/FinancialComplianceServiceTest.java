package com.budgetbuddy.compliance.financial;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyDouble;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.AuditLogService;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

/**
 * Unit Tests for FinancialComplianceService Tests financial compliance (PCI DSS, GLBA, SOX, FFIEC,
 * FINRA)
 */
@ExtendWith(MockitoExtension.class)
class FinancialComplianceServiceTest {

    @Mock private CloudWatchClient cloudWatchClient;

    @Mock private AuditLogService auditLogService;

    @InjectMocks private FinancialComplianceService financialComplianceService;

    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
    }

    @Test
    void testLogCardDataAccessWithEncryptedLogsAccess() {
        // Given
        final String cardLast4 = "1111";
        final boolean encrypted = true;
        doNothing().when(auditLogService).logCardDataAccess(anyString(), anyString(), anyBoolean());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCardDataAccess(testUserId, cardLast4, encrypted);

        // Then
        verify(auditLogService, times(1)).logCardDataAccess(testUserId, cardLast4, encrypted);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCardDataAccessWithUnencryptedLogsViolation() {
        // Given
        final String cardLast4 = "1111";
        final boolean encrypted = false;
        doNothing().when(auditLogService).logCardDataAccess(anyString(), anyString(), anyBoolean());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCardDataAccess(testUserId, cardLast4, encrypted);

        // Then
        verify(auditLogService, times(1)).logCardDataAccess(testUserId, cardLast4, encrypted);
        verify(cloudWatchClient, atLeast(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCardholderDataAccessWithAuthorizedLogsAccess() {
        // Given
        final String resource = "/api/accounts";
        final boolean authorized = true;
        doNothing()
                .when(auditLogService)
                .logCardholderDataAccess(anyString(), anyString(), anyBoolean());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCardholderDataAccess(testUserId, resource, authorized);

        // Then
        verify(auditLogService, times(1)).logCardholderDataAccess(testUserId, resource, authorized);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogFinancialDataAccessWithValidInputLogsAccess() {
        // Given
        final String dataType = "ACCOUNT_BALANCE";
        final String action = "READ";
        doNothing()
                .when(auditLogService)
                .logFinancialDataAccess(anyString(), anyString(), anyString());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logFinancialDataAccess(testUserId, dataType, action);

        // Then
        verify(auditLogService, times(1)).logFinancialDataAccess(testUserId, dataType, action);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogFinancialDataModificationWithSignificantChangeLogsWarning() {
        // Given
        final String dataType = "balance";
        final String beforeValue = "1000.00";
        final String afterValue = "5000.00";
        doNothing()
                .when(auditLogService)
                .logFinancialDataModification(anyString(), anyString(), anyString(), anyString());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logFinancialDataModification(
                testUserId, dataType, beforeValue, afterValue);

        // Then
        verify(auditLogService, times(1))
                .logFinancialDataModification(testUserId, dataType, beforeValue, afterValue);
        verify(cloudWatchClient, atLeast(2)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogInternalControlWithEffectiveLogsControl() {
        // Given
        final String controlId = "CTRL-001";
        final String activity = "ACCESS_REVIEW";
        final boolean effective = true;
        doNothing()
                .when(auditLogService)
                .logInternalControl(anyString(), anyString(), anyBoolean());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logInternalControl(controlId, activity, effective);

        // Then
        verify(auditLogService, times(1)).logInternalControl(controlId, activity, effective);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogSecurityControlWithPassStatusLogsControl() {
        // Given
        final String controlId = "SEC-001";
        final String status = "PASS";
        doNothing().when(auditLogService).logSecurityControl(anyString(), anyString());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logSecurityControl(controlId, status);

        // Then
        verify(auditLogService, times(1)).logSecurityControl(controlId, status);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCustomerAssetAccessWithValidInputLogsAccess() {
        // Given
        final String customerId = "customer-123";
        final String assetType = "ACCOUNT";
        final String action = "VIEW";
        doNothing()
                .when(auditLogService)
                .logCustomerAssetAccess(anyString(), anyString(), anyString(), anyString());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCustomerAssetAccess(
                testUserId, customerId, assetType, action);

        // Then
        verify(auditLogService, times(1))
                .logCustomerAssetAccess(testUserId, customerId, assetType, action);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogRecordKeepingWithValidInputLogsRecord() {
        // Given
        final String recordType = "TRANSACTION";
        final String recordId = "record-123";
        final Instant retentionUntil = Instant.now().plusSeconds(7L * 365 * 24 * 60 * 60);
        doNothing()
                .when(auditLogService)
                .logRecordKeeping(anyString(), anyString(), any(Instant.class));
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logRecordKeeping(recordType, recordId, retentionUntil);

        // Then
        verify(auditLogService, times(1)).logRecordKeeping(recordType, recordId, retentionUntil);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogSupervisionWithApprovedLogsSupervision() {
        // Given
        final String supervisorId = "supervisor-123";
        final String supervisedUserId = "user-456";
        final String activity = "TRANSACTION_REVIEW";
        final boolean approved = true;
        doNothing()
                .when(auditLogService)
                .logSupervision(anyString(), anyString(), anyString(), anyBoolean());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logSupervision(
                supervisorId, supervisedUserId, activity, approved);

        // Then
        verify(auditLogService, times(1))
                .logSupervision(supervisorId, supervisedUserId, activity, approved);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testReportSuspiciousActivityWithValidInputLogsActivity() {
        // Given
        final String activityType = "LARGE_TRANSACTION";
        final String details = "Transaction amount exceeds threshold";
        doNothing()
                .when(auditLogService)
                .logSuspiciousActivity(anyString(), anyString(), anyString());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.reportSuspiciousActivity(testUserId, activityType, details);

        // Then
        verify(auditLogService, times(1)).logSuspiciousActivity(testUserId, activityType, details);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCommunicationWithValidInputLogsCommunication() {
        // Given
        final String customerId = "customer-123";
        final String communicationType = "EMAIL";
        final String content = "Account statement";
        doNothing()
                .when(auditLogService)
                .logCommunication(anyString(), anyString(), anyString(), anyString());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCommunication(
                testUserId, customerId, communicationType, content);

        // Then
        verify(auditLogService, times(1))
                .logCommunication(testUserId, customerId, communicationType, content);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testMonitorTransactionWithSuspiciousAmountLogsSuspicious() {
        // Given
        final String transactionId = "txn-123";
        final double amount = 15000.00; // Over threshold
        doNothing()
                .when(auditLogService)
                .logSuspiciousTransaction(anyString(), anyDouble(), anyString());
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.monitorTransaction(transactionId, amount, testUserId);

        // Then
        verify(auditLogService, times(1))
                .logSuspiciousTransaction(transactionId, amount, testUserId);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testMonitorTransactionWithNormalAmountLogsTransaction() {
        // Given
        final String transactionId = "txn-123";
        final double amount = 100.00; // Under threshold
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.monitorTransaction(transactionId, amount, testUserId);

        // Then
        verify(auditLogService, never())
                .logSuspiciousTransaction(anyString(), anyDouble(), anyString());
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogDataRetentionWithValidInputLogsRetention() {
        // Given
        final String dataType = "TRANSACTION";
        final Instant retentionUntil = Instant.now().plusSeconds(7L * 365 * 24 * 60 * 60);
        doNothing().when(auditLogService).logDataRetention(anyString(), any(Instant.class));
        final PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logDataRetention(dataType, retentionUntil);

        // Then
        verify(auditLogService, times(1)).logDataRetention(dataType, retentionUntil);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }
}
