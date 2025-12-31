package com.budgetbuddy.api;

import com.budgetbuddy.compliance.AuditLogService;
import com.budgetbuddy.exception.AppException;
import com.budgetbuddy.exception.ErrorCode;
import com.budgetbuddy.plaid.PlaidWebhookService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for PlaidWebhookController
 */
@ExtendWith(MockitoExtension.class)
class PlaidWebhookControllerTest {

    @Mock
    private PlaidWebhookService webhookService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private PlaidWebhookController controller;

    private Map<String, Object> validPayload;

    @BeforeEach
    void setUp() {
        validPayload = new HashMap<>();
        validPayload.put("webhook_type", "TRANSACTIONS");
        validPayload.put("webhook_code", "SYNC_UPDATES_AVAILABLE");
        validPayload.put("item_id", "item-123");
    }

    @Test
    void testHandleWebhook_WithValidTransactionWebhook_ReturnsSuccess() {
        // Given
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(webhookService).handleTransactionWebhook(anyMap());
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), anyString(), anyMap(), isNull(), isNull());

        // When
        ResponseEntity<Map<String, String>> response = controller.handleWebhook(validPayload, "verification-header");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("success", response.getBody().get("status"));
        verify(webhookService).handleTransactionWebhook(validPayload);
    }

    @Test
    void testHandleWebhook_WithItemWebhook_ProcessesItem() {
        // Given
        validPayload.put("webhook_type", "ITEM");
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(webhookService).handleItemWebhook(anyMap());
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), anyString(), anyMap(), isNull(), isNull());

        // When
        ResponseEntity<Map<String, String>> response = controller.handleWebhook(validPayload, "verification-header");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(webhookService).handleItemWebhook(validPayload);
    }

    @Test
    void testHandleWebhook_WithAuthWebhook_ProcessesAuth() {
        // Given
        validPayload.put("webhook_type", "AUTH");
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(webhookService).handleAuthWebhook(anyMap());
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), anyString(), anyMap(), isNull(), isNull());

        // When
        ResponseEntity<Map<String, String>> response = controller.handleWebhook(validPayload, "verification-header");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(webhookService).handleAuthWebhook(validPayload);
    }

    @Test
    void testHandleWebhook_WithIncomeWebhook_ProcessesIncome() {
        // Given
        validPayload.put("webhook_type", "INCOME");
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(webhookService).handleIncomeWebhook(anyMap());
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), anyString(), anyMap(), isNull(), isNull());

        // When
        ResponseEntity<Map<String, String>> response = controller.handleWebhook(validPayload, "verification-header");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(webhookService).handleIncomeWebhook(validPayload);
    }

    @Test
    void testHandleWebhook_WithEmptyPayload_ReturnsBadRequest() {
        // Given
        Map<String, Object> emptyPayload = new HashMap<>();

        // When
        ResponseEntity<Map<String, String>> response = controller.handleWebhook(emptyPayload, "verification-header");

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void testHandleWebhook_WithNullPayload_ReturnsBadRequest() {
        // When
        ResponseEntity<Map<String, String>> response = controller.handleWebhook(null, "verification-header");

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void testHandleWebhook_WithMissingWebhookType_ReturnsBadRequest() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "SYNC_UPDATES_AVAILABLE");

        // When
        ResponseEntity<Map<String, String>> response = controller.handleWebhook(payload, "verification-header");

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("error", response.getBody().get("status"));
    }

    @Test
    void testHandleWebhook_WithInvalidSignature_ThrowsException() {
        // Given
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(false);

        // When/Then
        AppException exception = assertThrows(AppException.class, () -> 
                controller.handleWebhook(validPayload, "invalid-header"));
        assertEquals(ErrorCode.PLAID_WEBHOOK_ERROR, exception.getErrorCode());
    }

    @Test
    void testHandleWebhook_WithUnknownWebhookType_LogsWarning() {
        // Given
        validPayload.put("webhook_type", "UNKNOWN_TYPE");
        when(webhookService.verifyWebhookSignature(anyMap(), anyString())).thenReturn(true);
        doNothing().when(auditLogService).logAction(anyString(), anyString(), anyString(), anyString(), anyMap(), isNull(), isNull());

        // When
        ResponseEntity<Map<String, String>> response = controller.handleWebhook(validPayload, "verification-header");

        // Then
        assertEquals(HttpStatus.OK, response.getStatusCode());
        // Unknown webhook type should not call any handler
        verify(webhookService, never()).handleTransactionWebhook(anyMap());
        verify(webhookService, never()).handleItemWebhook(anyMap());
    }
}

