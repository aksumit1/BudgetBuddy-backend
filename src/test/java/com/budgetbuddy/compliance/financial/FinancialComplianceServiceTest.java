package com.budgetbuddy.compliance.financial;

import com.budgetbuddy.compliance.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for FinancialComplianceService
 * Tests financial compliance (PCI DSS, GLBA, SOX, FFIEC, FINRA)
 */
@ExtendWith(MockitoExtension.class)
class FinancialComplianceServiceTest {

    @Mock
    private CloudWatchClient cloudWatchClient;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FinancialComplianceService financialComplianceService;

    private String testUserId;

    @BeforeEach
    void setUp() {
        testUserId = "user-123";
    }

    @Test
    void testLogCardDataAccess_WithEncrypted_LogsAccess() {
        // Given
        String cardLast4 = "1111";
        boolean encrypted = true;
        doNothing().when(auditLogService).logCardDataAccess(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCardDataAccess(testUserId, cardLast4, encrypted);

        // Then
        verify(auditLogService, times(1)).logCardDataAccess(testUserId, cardLast4, encrypted);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCardDataAccess_WithUnencrypted_LogsViolation() {
        // Given
        String cardLast4 = "1111";
        boolean encrypted = false;
        doNothing().when(auditLogService).logCardDataAccess(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCardDataAccess(testUserId, cardLast4, encrypted);

        // Then
        verify(auditLogService, times(1)).logCardDataAccess(testUserId, cardLast4, encrypted);
        verify(cloudWatchClient, atLeast(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCardholderDataAccess_WithAuthorized_LogsAccess() {
        // Given
        String resource = "/api/accounts";
        boolean authorized = true;
        doNothing().when(auditLogService).logCardholderDataAccess(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCardholderDataAccess(testUserId, resource, authorized);

        // Then
        verify(auditLogService, times(1)).logCardholderDataAccess(testUserId, resource, authorized);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogFinancialDataAccess_WithValidInput_LogsAccess() {
        // Given
        String dataType = "ACCOUNT_BALANCE";
        String action = "READ";
        doNothing().when(auditLogService).logFinancialDataAccess(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logFinancialDataAccess(testUserId, dataType, action);

        // Then
        verify(auditLogService, times(1)).logFinancialDataAccess(testUserId, dataType, action);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogFinancialDataModification_WithSignificantChange_LogsWarning() {
        // Given
        String dataType = "balance";
        String beforeValue = "1000.00";
        String afterValue = "5000.00";
        doNothing().when(auditLogService).logFinancialDataModification(anyString(), anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logFinancialDataModification(testUserId, dataType, beforeValue, afterValue);

        // Then
        verify(auditLogService, times(1)).logFinancialDataModification(testUserId, dataType, beforeValue, afterValue);
        verify(cloudWatchClient, atLeast(2)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogInternalControl_WithEffective_LogsControl() {
        // Given
        String controlId = "CTRL-001";
        String activity = "ACCESS_REVIEW";
        boolean effective = true;
        doNothing().when(auditLogService).logInternalControl(anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logInternalControl(controlId, activity, effective);

        // Then
        verify(auditLogService, times(1)).logInternalControl(controlId, activity, effective);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogSecurityControl_WithPassStatus_LogsControl() {
        // Given
        String controlId = "SEC-001";
        String status = "PASS";
        doNothing().when(auditLogService).logSecurityControl(anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logSecurityControl(controlId, status);

        // Then
        verify(auditLogService, times(1)).logSecurityControl(controlId, status);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCustomerAssetAccess_WithValidInput_LogsAccess() {
        // Given
        String customerId = "customer-123";
        String assetType = "ACCOUNT";
        String action = "VIEW";
        doNothing().when(auditLogService).logCustomerAssetAccess(anyString(), anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCustomerAssetAccess(testUserId, customerId, assetType, action);

        // Then
        verify(auditLogService, times(1)).logCustomerAssetAccess(testUserId, customerId, assetType, action);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogRecordKeeping_WithValidInput_LogsRecord() {
        // Given
        String recordType = "TRANSACTION";
        String recordId = "record-123";
        Instant retentionUntil = Instant.now().plusSeconds(7L * 365 * 24 * 60 * 60);
        doNothing().when(auditLogService).logRecordKeeping(anyString(), anyString(), any(Instant.class));
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logRecordKeeping(recordType, recordId, retentionUntil);

        // Then
        verify(auditLogService, times(1)).logRecordKeeping(recordType, recordId, retentionUntil);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogSupervision_WithApproved_LogsSupervision() {
        // Given
        String supervisorId = "supervisor-123";
        String supervisedUserId = "user-456";
        String activity = "TRANSACTION_REVIEW";
        boolean approved = true;
        doNothing().when(auditLogService).logSupervision(anyString(), anyString(), anyString(), anyBoolean());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logSupervision(supervisorId, supervisedUserId, activity, approved);

        // Then
        verify(auditLogService, times(1)).logSupervision(supervisorId, supervisedUserId, activity, approved);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testReportSuspiciousActivity_WithValidInput_LogsActivity() {
        // Given
        String activityType = "LARGE_TRANSACTION";
        String details = "Transaction amount exceeds threshold";
        doNothing().when(auditLogService).logSuspiciousActivity(anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.reportSuspiciousActivity(testUserId, activityType, details);

        // Then
        verify(auditLogService, times(1)).logSuspiciousActivity(testUserId, activityType, details);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogCommunication_WithValidInput_LogsCommunication() {
        // Given
        String customerId = "customer-123";
        String communicationType = "EMAIL";
        String content = "Account statement";
        doNothing().when(auditLogService).logCommunication(anyString(), anyString(), anyString(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logCommunication(testUserId, customerId, communicationType, content);

        // Then
        verify(auditLogService, times(1)).logCommunication(testUserId, customerId, communicationType, content);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testMonitorTransaction_WithSuspiciousAmount_LogsSuspicious() {
        // Given
        String transactionId = "txn-123";
        double amount = 15000.00; // Over threshold
        doNothing().when(auditLogService).logSuspiciousTransaction(anyString(), anyDouble(), anyString());
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.monitorTransaction(transactionId, amount, testUserId);

        // Then
        verify(auditLogService, times(1)).logSuspiciousTransaction(transactionId, amount, testUserId);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testMonitorTransaction_WithNormalAmount_LogsTransaction() {
        // Given
        String transactionId = "txn-123";
        double amount = 100.00; // Under threshold
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.monitorTransaction(transactionId, amount, testUserId);

        // Then
        verify(auditLogService, never()).logSuspiciousTransaction(anyString(), anyDouble(), anyString());
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }

    @Test
    void testLogDataRetention_WithValidInput_LogsRetention() {
        // Given
        String dataType = "TRANSACTION";
        Instant retentionUntil = Instant.now().plusSeconds(7L * 365 * 24 * 60 * 60);
        doNothing().when(auditLogService).logDataRetention(anyString(), any(Instant.class));
        PutMetricDataResponse response = PutMetricDataResponse.builder().build();
        when(cloudWatchClient.putMetricData(any(PutMetricDataRequest.class))).thenReturn(response);

        // When
        financialComplianceService.logDataRetention(dataType, retentionUntil);

        // Then
        verify(auditLogService, times(1)).logDataRetention(dataType, retentionUntil);
        verify(cloudWatchClient, times(1)).putMetricData(any(PutMetricDataRequest.class));
    }
}

