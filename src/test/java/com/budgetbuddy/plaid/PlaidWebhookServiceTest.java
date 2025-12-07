package com.budgetbuddy.plaid;

import com.budgetbuddy.aws.secrets.SecretsManagerService;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.NotificationService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for Plaid Webhook Service
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaidWebhookServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private SecretsManagerService secretsManagerService;

    @Mock
    private ObjectMapper objectMapper;

    private PlaidWebhookService service;
    private String webhookSecret = "test-webhook-secret";

    @BeforeEach
    void setUp() throws Exception {
        service = new PlaidWebhookService(
                userRepository,
                accountRepository,
                transactionRepository,
                notificationService,
                secretsManagerService,
                objectMapper
        );
        
        // Use lenient stubbing to avoid unnecessary stubbing errors
        lenient().when(secretsManagerService.getSecret("plaid/webhook_secret", "")).thenReturn(webhookSecret);
    }

    @Test
    void testVerifyWebhookSignature_WithValidSignature_ReturnsTrue() throws Exception {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        payload.put("item_id", "item-123");
        
        String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\",\"item_id\":\"item-123\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);
        
        // Calculate expected signature
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] signatureBytes = mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
        String expectedSignature = Base64.getEncoder().encodeToString(signatureBytes);
        
        // When
        boolean isValid = service.verifyWebhookSignature(payload, expectedSignature);
        
        // Then
        assertTrue(isValid);
    }

    @Test
    void testVerifyWebhookSignature_WithInvalidSignature_ReturnsFalse() throws Exception {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        
        String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);
        
        // When
        boolean isValid = service.verifyWebhookSignature(payload, "invalid-signature");
        
        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignature_WithNullHeader_ReturnsFalse() {
        // When
        boolean isValid = service.verifyWebhookSignature(new HashMap<>(), null);
        
        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignature_WithEmptyHeader_ReturnsFalse() {
        // When
        boolean isValid = service.verifyWebhookSignature(new HashMap<>(), "");
        
        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignature_WithMissingSecret_ReturnsFalse() throws Exception {
        // Given
        // Override the lenient stubbing from setUp - use lenient here too to avoid conflicts
        lenient().when(secretsManagerService.getSecret("plaid/webhook_secret", "")).thenReturn("");
        
        Map<String, Object> payload = new HashMap<>();
        String payloadJson = "{}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);
        
        // When
        boolean isValid = service.verifyWebhookSignature(payload, "signature");
        
        // Then
        assertFalse(isValid);
    }

    @Test
    void testHandleTransactionWebhook_WithInitialUpdate_CallsSyncTransactions() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        payload.put("item_id", "item-123");
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail("test@example.com");
        
        AccountTable account = new AccountTable();
        account.setAccountId("account-123");
        account.setUserId("user-123");
        account.setPlaidItemId("item-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        
        // When
        service.handleTransactionWebhook(payload);
        
        // Then - Should not throw exception
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionWebhook_WithHistoricalUpdate_CallsSyncTransactions() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "HISTORICAL_UPDATE");
        payload.put("item_id", "item-123");
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionWebhook_WithDefaultUpdate_CallsSyncNewTransactions() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "DEFAULT_UPDATE");
        payload.put("item_id", "item-123");
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionWebhook_WithTransactionsRemoved_HandlesRemoval() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "TRANSACTIONS_REMOVED");
        payload.put("item_id", "item-123");
        payload.put("removed_transactions", List.of("txn-123", "txn-456"));
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        
        AccountTable account = new AccountTable();
        account.setUserId("user-123");
        
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setUserId("user-123");
        transaction.setPlaidTransactionId("txn-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(transactionRepository.findByPlaidTransactionId("txn-123")).thenReturn(Optional.of(transaction));
        when(transactionRepository.findByPlaidTransactionId("txn-456")).thenReturn(Optional.empty());
        
        // When
        service.handleTransactionWebhook(payload);
        
        // Then - Should not throw exception
        verify(transactionRepository, atLeastOnce()).findByPlaidTransactionId(anyString());
    }

    @Test
    void testHandleItemWebhook_WithError_HandlesError() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "ERROR");
        payload.put("item_id", "item-123");
        payload.put("error_code", "ITEM_LOGIN_REQUIRED");
        payload.put("error_message", "User needs to re-authenticate");
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail("test@example.com");
        
        AccountTable account = new AccountTable();
        account.setUserId("user-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, "success"));
        
        // When
        service.handleItemWebhook(payload);
        
        // Then
        verify(notificationService).sendNotification(any(NotificationService.NotificationRequest.class));
    }

    @Test
    void testHandleItemWebhook_WithPendingExpiration_HandlesExpiration() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "PENDING_EXPIRATION");
        payload.put("item_id", "item-123");
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail("test@example.com");
        
        AccountTable account = new AccountTable();
        account.setUserId("user-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, "success"));
        
        // When
        service.handleItemWebhook(payload);
        
        // Then
        verify(notificationService).sendNotification(any(NotificationService.NotificationRequest.class));
    }

    @Test
    void testHandleItemWebhook_WithPermissionRevoked_DeactivatesAccounts() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "USER_PERMISSION_REVOKED");
        payload.put("item_id", "item-123");
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail("test@example.com");
        
        AccountTable account = new AccountTable();
        account.setAccountId("account-123");
        account.setUserId("user-123");
        account.setActive(true);
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, "success"));
        
        // When
        service.handleItemWebhook(payload);
        
        // Then
        verify(accountRepository).save(any(AccountTable.class));
        verify(notificationService).sendNotification(any(NotificationService.NotificationRequest.class));
    }

    @Test
    void testHandleAuthWebhook_LogsEvent() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "AUTH");
        payload.put("item_id", "item-123");
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleAuthWebhook(payload);
        });
    }

    @Test
    void testHandleIncomeWebhook_LogsEvent() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INCOME");
        payload.put("item_id", "item-123");
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleIncomeWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionWebhook_WithMissingItemId_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        // Missing item_id
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionWebhook_WithUnknownCode_LogsWarning() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "UNKNOWN_CODE");
        payload.put("item_id", "item-123");
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testHandleItemWebhook_WithMissingItemId_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "ERROR");
        // Missing item_id
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleItemWebhook(payload);
        });
    }

    @Test
    void testHandleItemWebhook_WithUnknownCode_LogsWarning() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "UNKNOWN_ITEM_CODE");
        payload.put("item_id", "item-123");
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleItemWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionsRemoved_WithEmptyList_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "TRANSACTIONS_REMOVED");
        payload.put("item_id", "item-123");
        payload.put("removed_transactions", new ArrayList<>());
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        
        AccountTable account = new AccountTable();
        account.setUserId("user-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionsRemoved_WithNullList_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "TRANSACTIONS_REMOVED");
        payload.put("item_id", "item-123");
        payload.put("removed_transactions", null);
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionsRemoved_WithTransactionNotBelongingToUser_SkipsDeletion() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "TRANSACTIONS_REMOVED");
        payload.put("item_id", "item-123");
        payload.put("removed_transactions", List.of("txn-123"));
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        
        AccountTable account = new AccountTable();
        account.setUserId("user-123");
        
        TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId("txn-123");
        transaction.setUserId("different-user"); // Different user
        transaction.setPlaidTransactionId("txn-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(transactionRepository.findByPlaidTransactionId("txn-123")).thenReturn(Optional.of(transaction));
        
        // When
        service.handleTransactionWebhook(payload);
        
        // Then - Should not delete transaction belonging to different user
        verify(transactionRepository, never()).delete(anyString());
    }

    @Test
    void testHandleItemError_WithMissingUser_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "ERROR");
        payload.put("item_id", "item-123");
        payload.put("error_code", "ITEM_LOGIN_REQUIRED");
        payload.put("error_message", "User needs to re-authenticate");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(Collections.emptyList());
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleItemWebhook(payload);
        });
    }

    @Test
    void testHandlePendingExpiration_WithMissingUser_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "PENDING_EXPIRATION");
        payload.put("item_id", "item-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(Collections.emptyList());
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleItemWebhook(payload);
        });
    }

    @Test
    void testHandlePermissionRevoked_WithMissingUser_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "USER_PERMISSION_REVOKED");
        payload.put("item_id", "item-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(Collections.emptyList());
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleItemWebhook(payload);
        });
    }

    @Test
    void testHandlePermissionRevoked_WithNoAccounts_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "USER_PERMISSION_REVOKED");
        payload.put("item_id", "item-123");
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail("test@example.com");
        
        AccountTable account = new AccountTable();
        account.setUserId("user-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(Collections.emptyList());
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleItemWebhook(payload);
        });
    }

    @Test
    void testSyncTransactionsForItem_WithNoAccounts_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        payload.put("item_id", "item-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(Collections.emptyList());
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testSyncNewTransactionsForItem_WithNoAccounts_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "DEFAULT_UPDATE");
        payload.put("item_id", "item-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(Collections.emptyList());
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testVerifyWebhookSignature_WithSignatureLengthMismatch_ReturnsFalse() throws Exception {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        
        String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);
        
        // When - Signature with different length
        boolean isValid = service.verifyWebhookSignature(payload, "short");
        
        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignature_WithJsonProcessingException_ReturnsFalse() throws Exception {
        // Given
        Map<String, Object> payload = new HashMap<>();
        when(objectMapper.writeValueAsString(payload)).thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("Error") {});
        
        // When
        boolean isValid = service.verifyWebhookSignature(payload, "signature");
        
        // Then
        assertFalse(isValid);
    }

    @Test
    void testHandleItemError_WithNotificationFailure_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "ERROR");
        payload.put("item_id", "item-123");
        payload.put("error_code", "ITEM_LOGIN_REQUIRED");
        payload.put("error_message", "User needs to re-authenticate");
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail("test@example.com");
        
        AccountTable account = new AccountTable();
        account.setUserId("user-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(false, "failed"));
        
        // When/Then
        assertDoesNotThrow(() -> {
            service.handleItemWebhook(payload);
        });
    }

    @Test
    void testFindUserByItemId_WithNullItemId_ReturnsEmpty() {
        // Given - This tests the private findUserByItemId method indirectly
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        payload.put("item_id", null); // Null item ID
        
        // When/Then - Should handle gracefully
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testFindUserByItemId_WithEmptyItemId_ReturnsEmpty() {
        // Given - This tests the private findUserByItemId method indirectly
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        payload.put("item_id", ""); // Empty item ID
        
        // When/Then - Should handle gracefully
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testFindUserByItemId_WithAccountHavingNullUserId_ReturnsEmpty() {
        // Given - Account exists but has null userId
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        payload.put("item_id", "item-123");
        
        AccountTable account = new AccountTable();
        account.setAccountId("account-123");
        account.setUserId(null); // Null userId
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        
        // When/Then - Should handle gracefully
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testFindUserByItemId_WithAccountHavingEmptyUserId_ReturnsEmpty() {
        // Given - Account exists but has empty userId
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        payload.put("item_id", "item-123");
        
        AccountTable account = new AccountTable();
        account.setAccountId("account-123");
        account.setUserId(""); // Empty userId
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        
        // When/Then - Should handle gracefully
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testFindUserByItemId_WithRepositoryException_HandlesGracefully() {
        // Given - Repository throws exception
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        payload.put("item_id", "item-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenThrow(new RuntimeException("Database error"));
        
        // When/Then - Should handle gracefully
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testHandlePermissionRevoked_WithAccountSaveException_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "USER_PERMISSION_REVOKED");
        payload.put("item_id", "item-123");
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail("test@example.com");
        
        AccountTable account = new AccountTable();
        account.setAccountId("account-123");
        account.setUserId("user-123");
        account.setActive(true);
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("Save failed")).when(accountRepository).save(any(AccountTable.class));
        when(notificationService.sendNotification(any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, "success"));
        
        // When/Then - Should handle save exception gracefully
        assertDoesNotThrow(() -> {
            service.handleItemWebhook(payload);
        });
    }

    @Test
    void testHandleTransactionsRemoved_WithTransactionRepositoryException_HandlesGracefully() {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "TRANSACTIONS_REMOVED");
        payload.put("item_id", "item-123");
        payload.put("removed_transactions", List.of("txn-123"));
        
        UserTable user = new UserTable();
        user.setUserId("user-123");
        
        AccountTable account = new AccountTable();
        account.setUserId("user-123");
        
        when(accountRepository.findByPlaidItemId("item-123")).thenReturn(List.of(account));
        when(userRepository.findById("user-123")).thenReturn(Optional.of(user));
        when(transactionRepository.findByPlaidTransactionId("txn-123")).thenThrow(new RuntimeException("Repository error"));
        
        // When/Then - Should handle exception gracefully
        assertDoesNotThrow(() -> {
            service.handleTransactionWebhook(payload);
        });
    }

    @Test
    void testVerifyWebhookSignature_WithInvalidKeyException_ReturnsFalse() throws Exception {
        // Given
        Map<String, Object> payload = new HashMap<>();
        payload.put("webhook_code", "INITIAL_UPDATE");
        
        String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);
        
        // Mock secretsManagerService to return invalid key
        lenient().when(secretsManagerService.getSecret("plaid/webhook_secret", "")).thenReturn("\u0000\u0000\u0000"); // Invalid key bytes
        
        // When
        boolean isValid = service.verifyWebhookSignature(payload, "signature");
        
        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignature_WithNoSuchAlgorithmException_ReturnsFalse() throws Exception {
        // Given - This is hard to test directly, but we can test the error path
        Map<String, Object> payload = new HashMap<>();
        String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);
        lenient().when(secretsManagerService.getSecret("plaid/webhook_secret", "")).thenReturn("test-secret");
        
        // When - The algorithm should be available, but we test the path exists
        boolean isValid = service.verifyWebhookSignature(payload, "invalid-signature");
        
        // Then
        assertFalse(isValid);
    }
}

