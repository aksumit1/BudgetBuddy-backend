package com.budgetbuddy.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.plaid.PlaidWebhookService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Unit Tests for PlaidWebhookController */
@ExtendWith(MockitoExtension.class)
class PlaidWebhookControllerTest {

    private static final String VERIFICATION_HEADER = "verification-header";
    private static final String WEBHOOK_TYPE = "webhook_type";

    @Mock private PlaidWebhookService webhookService;

    @Mock private AuditLogService auditLogService;

    @InjectMocks private PlaidWebhookController controller;

    private Map<String, Object> validPayload;

    @BeforeEach
    void setUp() {
        validPayload = new HashMap<>();
        validPayload.put(WEBHOOK_TYPE, "TRANSACTIONS");
        validPayload.put("webhook_code", "SYNC_UPDATES_AVAILABLE");
        validPayload.put("item_id", "item-123");
    }

    @Test
    void testHandleWebhookWithValidTransactionWebhookReturnsSuccess() {
        // Given
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(webhookService).handleTransactionWebhook(anyMap());
        doNothing()
                .when(auditLogService)
                .logAction(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyMap(),
                        isNull(),
                        isNull());

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(validPayload, VERIFICATION_HEADER);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody().get("status"));
        verify(webhookService).handleTransactionWebhook(validPayload);
    }

    @Test
    void testHandleWebhookWithItemWebhookProcessesItem() {
        // Given
        validPayload.put(WEBHOOK_TYPE, "ITEM");
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(webhookService).handleItemWebhook(anyMap());
        doNothing()
                .when(auditLogService)
                .logAction(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyMap(),
                        isNull(),
                        isNull());

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(validPayload, VERIFICATION_HEADER);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(webhookService).handleItemWebhook(validPayload);
    }

    @Test
    void testHandleWebhookWithAuthWebhookProcessesAuth() {
        // Given
        validPayload.put(WEBHOOK_TYPE, "AUTH");
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(webhookService).handleAuthWebhook(anyMap());
        doNothing()
                .when(auditLogService)
                .logAction(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyMap(),
                        isNull(),
                        isNull());

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(validPayload, VERIFICATION_HEADER);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(webhookService).handleAuthWebhook(validPayload);
    }

    @Test
    void testHandleWebhookWithIncomeWebhookProcessesIncome() {
        // Given
        validPayload.put(WEBHOOK_TYPE, "INCOME");
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(webhookService).handleIncomeWebhook(anyMap());
        doNothing()
                .when(auditLogService)
                .logAction(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyMap(),
                        isNull(),
                        isNull());

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(validPayload, VERIFICATION_HEADER);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(webhookService).handleIncomeWebhook(validPayload);
    }

    @Test
    void testHandleWebhookWithEmptyPayloadReturnsBadRequest() {
        // Given
        final Map<String, Object> emptyPayload = new HashMap<>();

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(emptyPayload, VERIFICATION_HEADER);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void testHandleWebhookWithNullPayloadReturnsBadRequest() {
        // When
        final ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(null, VERIFICATION_HEADER);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void testHandleWebhookWithMissingWebhookTypeReturnsBadRequest() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "SYNC_UPDATES_AVAILABLE");

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(payload, VERIFICATION_HEADER);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void testHandleWebhookWithInvalidSignatureThrowsException() {
        // Given
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(false);

        // When/Then
        final AppException exception =
                assertThrows(
                        AppException.class,
                        () -> controller.handleWebhook(validPayload, "invalid-header"));
        assertEquals(ErrorCode.PLAID_WEBHOOK_ERROR, exception.getErrorCode());
    }

    @Test
    void testHandleWebhookWithUnknownWebhookTypeLogsWarning() {
        // Given
        validPayload.put(WEBHOOK_TYPE, "UNKNOWN_TYPE");
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing()
                .when(auditLogService)
                .logAction(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyMap(),
                        isNull(),
                        isNull());

        // When
        final ResponseEntity<Map<String, String>> response =
                controller.handleWebhook(validPayload, VERIFICATION_HEADER);

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Unknown webhook type should not call any handler
        verify(webhookService, never()).handleTransactionWebhook(anyMap());
        verify(webhookService, never()).handleItemWebhook(anyMap());
    }
}
