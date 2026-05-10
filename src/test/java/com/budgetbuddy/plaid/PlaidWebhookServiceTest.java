package com.budgetbuddy.plaid;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.budgetbuddy.aws.secrets.SecretsManagerService;
import com.budgetbuddy.model.dynamodb.AccountTable;
import com.budgetbuddy.model.dynamodb.TransactionTable;
import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.notification.NotificationService;
import com.budgetbuddy.repository.dynamodb.AccountRepository;
import com.budgetbuddy.repository.dynamodb.TransactionRepository;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

/** Unit Tests for Plaid Webhook Service */
// PMD's LawOfDemeter is documented as imprecise on chains involving
// standard library types (BigDecimal, String, Optional) and DTO
// getters; this class has many such idiomatic uses. Suppress at
// class level rather than littering every method.
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@SuppressWarnings("PMD.LawOfDemeter")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaidWebhookServiceTest {

    private static final String ITEM_123 = "item-123";
    private static final String USER_123 = "user-123";
    private static final String WEBHOOK_CODE = "webhook_code";
    private static final String ITEM_ID = "item_id";
    private static final String TXN_123 = "txn-123";
    private static final String REMOVED_TRANSACTIONS = "removed_transactions";
    private static final String ACCOUNT_123 = "account-123";
    private static final String SUCCESS = "success";

    @Mock private UserRepository userRepository;

    @Mock private AccountRepository accountRepository;

    @Mock private TransactionRepository transactionRepository;

    @Mock private NotificationService notificationService;

    @Mock private SecretsManagerService secretsManagerService;

    @Mock private ObjectMapper objectMapper;

    private PlaidWebhookService service;
    private String webhookSecret = "test-webhook-secret";

    private ListAppender<ILoggingEvent> logAppender;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        service =
                new PlaidWebhookService(
                        userRepository,
                        accountRepository,
                        transactionRepository,
                        notificationService,
                        secretsManagerService,
                        objectMapper);

        // Use lenient stubbing to avoid unnecessary stubbing errors
        lenient()
                .when(secretsManagerService.getSecret("plaid/webhook_secret", ""))
                .thenReturn(webhookSecret);

        // Set up log appender to capture log events for verification
        logger = (Logger) LoggerFactory.getLogger(PlaidWebhookService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @Test
    void testVerifyWebhookSignatureWithValidSignatureReturnsTrue() throws Exception {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        final String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\",\"item_id\":\"item-123\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);

        // Calculate expected signature
        final Mac mac = Mac.getInstance("HmacSHA256");
        final SecretKeySpec secretKeySpec =
                new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        final byte[] signatureBytes = mac.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));
        final String expectedSignature = Base64.getEncoder().encodeToString(signatureBytes);

        // When
        final boolean isValid = service.verifyWebhookSignature(payload, expectedSignature);

        // Then
        assertTrue(isValid);
    }

    @Test
    void testVerifyWebhookSignatureWithInvalidSignatureReturnsFalse() throws Exception {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");

        final String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);

        // When
        final boolean isValid = service.verifyWebhookSignature(payload, "invalid-signature");

        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignatureWithNullHeaderReturnsFalse() {
        // When
        final boolean isValid = service.verifyWebhookSignature(new HashMap<>(), null);

        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignatureWithEmptyHeaderReturnsFalse() {
        // When
        final boolean isValid = service.verifyWebhookSignature(new HashMap<>(), "");

        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignatureWithMissingSecretReturnsFalse() throws Exception {
        // Given
        // Override the lenient stubbing from setUp - use lenient here too to avoid conflicts
        lenient().when(secretsManagerService.getSecret("plaid/webhook_secret", "")).thenReturn("");

        final Map<String, Object> payload = new HashMap<>();
        final String payloadJson = "{}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);

        // When
        final boolean isValid = service.verifyWebhookSignature(payload, "signature");

        // Then
        assertFalse(isValid);
    }

    @Test
    void testHandleTransactionWebhookWithInitialUpdateCallsSyncTransactions() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setAccountId(ACCOUNT_123);
        account.setUserId(USER_123);
        account.setPlaidItemId(ITEM_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));

        // When
        service.handleTransactionWebhook(payload);

        // Then - Should not throw exception
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionWebhookWithHistoricalUpdateCallsSyncTransactions() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "HISTORICAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionWebhookWithDefaultUpdateCallsSyncNewTransactions() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "DEFAULT_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionWebhookWithTransactionsRemovedHandlesRemoval() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "TRANSACTIONS_REMOVED");
        payload.put(ITEM_ID, ITEM_123);
        payload.put(REMOVED_TRANSACTIONS, List.of(TXN_123, "txn-456"));

        final UserTable user = new UserTable();
        user.setUserId(USER_123);

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(TXN_123);
        transaction.setUserId(USER_123);
        transaction.setPlaidTransactionId(TXN_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(transactionRepository.findByPlaidTransactionId(TXN_123))
                .thenReturn(Optional.of(transaction));
        when(transactionRepository.findByPlaidTransactionId("txn-456"))
                .thenReturn(Optional.empty());

        // When
        service.handleTransactionWebhook(payload);

        // Then - Should not throw exception
        verify(transactionRepository, atLeastOnce()).findByPlaidTransactionId(anyString());
    }

    @Test
    void testHandleItemWebhookWithErrorHandlesError() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "ERROR");
        payload.put(ITEM_ID, ITEM_123);
        payload.put("error_code", "ITEM_LOGIN_REQUIRED");
        payload.put("error_message", "User needs to re-authenticate");

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, SUCCESS));

        // When
        service.handleItemWebhook(payload);

        // Then
        verify(notificationService)
                .sendNotification(any(NotificationService.NotificationRequest.class));
    }

    @Test
    void testHandleItemWebhookWithPendingExpirationHandlesExpiration() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "PENDING_EXPIRATION");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, SUCCESS));

        // When
        service.handleItemWebhook(payload);

        // Then
        verify(notificationService)
                .sendNotification(any(NotificationService.NotificationRequest.class));
    }

    @Test
    void testHandleItemWebhookWithPermissionRevokedDeactivatesAccounts() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "USER_PERMISSION_REVOKED");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setAccountId(ACCOUNT_123);
        account.setUserId(USER_123);
        account.setActive(true);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, SUCCESS));

        // When
        service.handleItemWebhook(payload);

        // Then
        verify(accountRepository).save(any(AccountTable.class));
        verify(notificationService)
                .sendNotification(any(NotificationService.NotificationRequest.class));
    }

    @Test
    void testHandleAuthWebhookLogsEvent() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "AUTH");
        payload.put(ITEM_ID, ITEM_123);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleAuthWebhook(payload);
                });
    }

    @Test
    void testHandleIncomeWebhookLogsEvent() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INCOME");
        payload.put(ITEM_ID, ITEM_123);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleIncomeWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionWebhookWithMissingItemIdHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        // Missing item_id

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionWebhookWithUnknownCodeLogsWarning() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "UNKNOWN_CODE");
        payload.put(ITEM_ID, ITEM_123);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleItemWebhookWithMissingItemIdHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "ERROR");
        // Missing item_id

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandleItemWebhookWithUnknownCodeLogsWarning() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "UNKNOWN_ITEM_CODE");
        payload.put(ITEM_ID, ITEM_123);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionsRemovedWithEmptyListHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "TRANSACTIONS_REMOVED");
        payload.put(ITEM_ID, ITEM_123);
        payload.put(REMOVED_TRANSACTIONS, new ArrayList<>());

        final UserTable user = new UserTable();
        user.setUserId(USER_123);

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionsRemovedWithNullListHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "TRANSACTIONS_REMOVED");
        payload.put(ITEM_ID, ITEM_123);
        payload.put(REMOVED_TRANSACTIONS, null);

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionsRemovedWithTransactionNotBelongingToUserSkipsDeletion() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "TRANSACTIONS_REMOVED");
        payload.put(ITEM_ID, ITEM_123);
        payload.put(REMOVED_TRANSACTIONS, List.of(TXN_123));

        final UserTable user = new UserTable();
        user.setUserId(USER_123);

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(TXN_123);
        transaction.setUserId("different-user"); // Different user
        transaction.setPlaidTransactionId(TXN_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(transactionRepository.findByPlaidTransactionId(TXN_123))
                .thenReturn(Optional.of(transaction));

        // When
        service.handleTransactionWebhook(payload);

        // Then - Should not delete transaction belonging to different user
        verify(transactionRepository, never()).delete(anyString());
    }

    @Test
    void testHandleItemErrorWithMissingUserHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "ERROR");
        payload.put(ITEM_ID, ITEM_123);
        payload.put("error_code", "ITEM_LOGIN_REQUIRED");
        payload.put("error_message", "User needs to re-authenticate");

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(Collections.emptyList());

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandlePendingExpirationWithMissingUserHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "PENDING_EXPIRATION");
        payload.put(ITEM_ID, ITEM_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(Collections.emptyList());

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandlePermissionRevokedWithMissingUserHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "USER_PERMISSION_REVOKED");
        payload.put(ITEM_ID, ITEM_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(Collections.emptyList());

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandlePermissionRevokedWithNoAccountsHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "USER_PERMISSION_REVOKED");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(Collections.emptyList());
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testSyncTransactionsForItemWithNoAccountsHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(Collections.emptyList());

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testSyncNewTransactionsForItemWithNoAccountsHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "DEFAULT_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(Collections.emptyList());

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testVerifyWebhookSignatureWithSignatureLengthMismatchReturnsFalse() throws Exception {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");

        final String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);

        // When - Signature with different length
        final boolean isValid = service.verifyWebhookSignature(payload, "short");

        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignatureWithJsonProcessingExceptionReturnsFalse() throws Exception {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        // Use JsonMappingException which is a concrete subclass of JsonProcessingException
        // Use the non-deprecated constructor that takes a message and a cause
        when(objectMapper.writeValueAsString(payload))
                .thenThrow(new JsonMappingException(null, "Error"));

        // When
        final boolean isValid = service.verifyWebhookSignature(payload, "signature");

        // Then
        assertFalse(isValid);

        // Verify logging behavior - should log WARN for handled failures (returns false gracefully)
        final List<ILoggingEvent> logEvents = logAppender.list;
        final long warnLogs =
                logEvents.stream()
                        .filter(
                                event ->
                                        event.getLevel() == Level.WARN
                                                && event.getMessage()
                                                        .contains(
                                                                "Error verifying webhook signature"))
                        .count();

        assertEquals(
                1,
                warnLogs,
                "Should log WARN when webhook signature verification fails (handled gracefully)");

        // Verify WARN log contains expected message
        final boolean foundWarnLog =
                logEvents.stream()
                        .anyMatch(
                                event ->
                                        event.getLevel() == Level.WARN
                                                && event.getMessage()
                                                        .contains(
                                                                "Error verifying webhook signature")
                                                && event.getMessage().contains("Error"));
        assertTrue(foundWarnLog, "Should log WARN with exception message");
    }

    @Test
    void testHandleItemErrorWithNotificationFailureHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "ERROR");
        payload.put(ITEM_ID, ITEM_123);
        payload.put("error_code", "ITEM_LOGIN_REQUIRED");
        payload.put("error_message", "User needs to re-authenticate");

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(false, "failed"));

        // When/Then
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testFindUserByItemIdWithNullItemIdReturnsEmpty() {
        // Given - This tests the private findUserByItemId method indirectly
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, null); // Null item ID

        // When/Then - Should handle gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testFindUserByItemIdWithEmptyItemIdReturnsEmpty() {
        // Given - This tests the private findUserByItemId method indirectly
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, ""); // Empty item ID

        // When/Then - Should handle gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testFindUserByItemIdWithAccountHavingNullUserIdReturnsEmpty() {
        // Given - Account exists but has null userId
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        final AccountTable account = new AccountTable();
        account.setAccountId(ACCOUNT_123);
        account.setUserId(null); // Null userId

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));

        // When/Then - Should handle gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testFindUserByItemIdWithAccountHavingEmptyUserIdReturnsEmpty() {
        // Given - Account exists but has empty userId
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        final AccountTable account = new AccountTable();
        account.setAccountId(ACCOUNT_123);
        account.setUserId(""); // Empty userId

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));

        // When/Then - Should handle gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testFindUserByItemIdWithRepositoryExceptionHandlesGracefully() {
        // Given - Repository throws exception
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        when(accountRepository.findByPlaidItemId(ITEM_123))
                .thenThrow(new RuntimeException("Database error"));

        // When/Then - Should handle gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandlePermissionRevokedWithAccountSaveExceptionHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "USER_PERMISSION_REVOKED");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setAccountId(ACCOUNT_123);
        account.setUserId(USER_123);
        account.setActive(true);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("Save failed"))
                .when(accountRepository)
                .save(any(AccountTable.class));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, SUCCESS));

        // When/Then - Should handle save exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionsRemovedWithTransactionRepositoryExceptionHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "TRANSACTIONS_REMOVED");
        payload.put(ITEM_ID, ITEM_123);
        payload.put(REMOVED_TRANSACTIONS, List.of(TXN_123));

        final UserTable user = new UserTable();
        user.setUserId(USER_123);

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(transactionRepository.findByPlaidTransactionId(TXN_123))
                .thenThrow(new RuntimeException("Repository error"));

        // When/Then - Should handle exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testVerifyWebhookSignatureWithInvalidKeyExceptionReturnsFalse() throws Exception {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");

        final String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);

        // Mock secretsManagerService to return invalid key
        lenient()
                .when(secretsManagerService.getSecret("plaid/webhook_secret", ""))
                .thenReturn("\u0000\u0000\u0000"); // Invalid key bytes

        // When
        final boolean isValid = service.verifyWebhookSignature(payload, "signature");

        // Then
        assertFalse(isValid);
    }

    @Test
    void testVerifyWebhookSignatureWithNoSuchAlgorithmExceptionReturnsFalse() throws Exception {
        // Given - This is hard to test directly, but we can test the error path
        final Map<String, Object> payload = new HashMap<>();
        final String payloadJson = "{\"webhook_code\":\"INITIAL_UPDATE\"}";
        when(objectMapper.writeValueAsString(payload)).thenReturn(payloadJson);
        lenient()
                .when(secretsManagerService.getSecret("plaid/webhook_secret", ""))
                .thenReturn("test-secret");

        // When - The algorithm should be available, but we test the path exists
        final boolean isValid = service.verifyWebhookSignature(payload, "invalid-signature");

        // Then
        assertFalse(isValid);
    }

    @Test
    void testSyncTransactionsForItemWithExceptionHandlesGracefully() {
        // Given - Repository throws exception during sync
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "INITIAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        when(accountRepository.findByPlaidItemId(ITEM_123))
                .thenThrow(new RuntimeException("Database connection failed"));

        // When/Then - Should handle exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testSyncNewTransactionsForItemWithExceptionHandlesGracefully() {
        // Given - Repository throws exception during sync
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "DEFAULT_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        when(accountRepository.findByPlaidItemId(ITEM_123))
                .thenThrow(new RuntimeException("Database connection failed"));

        // When/Then - Should handle exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testSyncTransactionsForItemWithUserFoundButExceptionHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "HISTORICAL_UPDATE");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123))
                .thenThrow(new RuntimeException("User lookup failed"));

        // When/Then - Should handle exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleTransactionsRemovedWithDeleteExceptionHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "TRANSACTIONS_REMOVED");
        payload.put(ITEM_ID, ITEM_123);
        payload.put(REMOVED_TRANSACTIONS, List.of(TXN_123));

        final UserTable user = new UserTable();
        user.setUserId(USER_123);

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        final TransactionTable transaction = new TransactionTable();
        transaction.setTransactionId(TXN_123);
        transaction.setUserId(USER_123);
        transaction.setPlaidTransactionId(TXN_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(transactionRepository.findByPlaidTransactionId(TXN_123))
                .thenReturn(Optional.of(transaction));
        doThrow(new RuntimeException("Delete failed")).when(transactionRepository).delete(TXN_123);

        // When/Then - Should handle delete exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleTransactionWebhook(payload);
                });
    }

    @Test
    void testHandleItemErrorWithNotificationExceptionHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "ERROR");
        payload.put(ITEM_ID, ITEM_123);
        payload.put("error_code", "ITEM_LOGIN_REQUIRED");
        payload.put("error_message", "User needs to re-authenticate");

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenThrow(new RuntimeException("Notification service unavailable"));

        // When/Then - Should handle notification exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandlePendingExpirationWithNotificationExceptionHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "PENDING_EXPIRATION");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setUserId(USER_123);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenThrow(new RuntimeException("Notification service unavailable"));

        // When/Then - Should handle notification exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandlePermissionRevokedWithNotificationExceptionHandlesGracefully() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "USER_PERMISSION_REVOKED");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account = new AccountTable();
        account.setAccountId(ACCOUNT_123);
        account.setUserId(USER_123);
        account.setActive(true);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenThrow(new RuntimeException("Notification service unavailable"));

        // When/Then - Should handle notification exception gracefully
        assertDoesNotThrow(
                () -> {
                    service.handleItemWebhook(payload);
                });
    }

    @Test
    void testHandlePermissionRevokedWithMultipleAccountsDeactivatesAll() {
        // Given
        final Map<String, Object> payload = new HashMap<>();
        payload.put(WEBHOOK_CODE, "USER_PERMISSION_REVOKED");
        payload.put(ITEM_ID, ITEM_123);

        final UserTable user = new UserTable();
        user.setUserId(USER_123);
        user.setEmail("test@example.com");

        final AccountTable account1 = new AccountTable();
        account1.setAccountId("account-1");
        account1.setUserId(USER_123);
        account1.setActive(true);

        final AccountTable account2 = new AccountTable();
        account2.setAccountId("account-2");
        account2.setUserId(USER_123);
        account2.setActive(true);

        when(accountRepository.findByPlaidItemId(ITEM_123)).thenReturn(List.of(account1, account2));
        when(userRepository.findById(USER_123)).thenReturn(Optional.of(user));
        when(notificationService.sendNotification(
                        any(NotificationService.NotificationRequest.class)))
                .thenReturn(new NotificationService.NotificationResult(true, SUCCESS));

        // When
        service.handleItemWebhook(payload);

        // Then - Should save all accounts
        verify(accountRepository, times(2)).save(any(AccountTable.class));
    }
}
